package it.danielebufarini.trenify.provider.mit.strikes

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import it.danielebufarini.trenify.core.model.Operator
import it.danielebufarini.trenify.core.model.ProviderId
import it.danielebufarini.trenify.core.model.RailwayTime
import it.danielebufarini.trenify.core.model.StrikeRelevance
import it.danielebufarini.trenify.core.model.StrikeStatus
import it.danielebufarini.trenify.core.model.strikeIntervalsOverlap
import it.danielebufarini.trenify.core.provider.api.ProviderFailure
import it.danielebufarini.trenify.core.provider.api.ProviderMetadata
import it.danielebufarini.trenify.core.provider.api.ProviderResult
import it.danielebufarini.trenify.core.provider.api.ProviderStrike
import it.danielebufarini.trenify.core.provider.api.StrikeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlin.time.Clock
import kotlin.time.Instant

class MitStrikeAdapter(
    private val client: HttpClient,
    private val clock: Clock = Clock.System,
    private val rssUrl: String = RSS_URL,
) : StrikeProvider {
    override val id = ProviderId("mit-strikes")
    override val suppliesCompleteSnapshots = true

    override suspend fun getStrikes(
        from: Instant,
        to: Instant,
        includeRevoked: Boolean,
    ): ProviderResult<List<ProviderStrike>> {
        require(to > from)
        val response = try {
            client.get(rssUrl) {
                headers[HttpHeaders.Accept] = "application/rss+xml, application/xml;q=0.9"
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: HttpRequestTimeoutException) {
            return ProviderResult.Unavailable(true, ProviderFailure.TIMEOUT)
        } catch (_: Exception) {
            return ProviderResult.Unavailable(true, ProviderFailure.TRANSPORT)
        }
        return when (response.status.value) {
            204 -> ProviderResult.NotFound
            404 -> ProviderResult.Unavailable(false, ProviderFailure.PROTOCOL)
            429, in 500..599 -> ProviderResult.Unavailable(true, ProviderFailure.UNAVAILABLE)
            !in 200..299 -> ProviderResult.Unavailable(false, ProviderFailure.PROTOCOL)
            else -> {
                val body = try {
                    response.bodyAsText()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return ProviderResult.Unavailable(true, ProviderFailure.TRANSPORT)
                }
                try {
                    val parsed = MitRssParser.parseComplete(body)
                    val strikes = parsed.strikes
                        .filter { strikeIntervalsOverlap(it.start, it.end, from, to) }
                        .filter { includeRevoked || it.status != StrikeStatus.REVOKED }
                    ProviderResult.Success(strikes, ProviderMetadata(id, clock.now()), parsed.complete)
                } catch (_: IllegalArgumentException) {
                    ProviderResult.Unavailable(false, ProviderFailure.PARSING)
                } catch (_: IllegalStateException) {
                    ProviderResult.Unavailable(false, ProviderFailure.PARSING)
                }
            }
        }
    }

    companion object {
        const val RSS_URL = "https://scioperi.mit.gov.it/mit2/public/scioperi/rss"
    }
}

/**
 * Parsed RSS snapshot with provider-neutral completeness (T7.12-A).
 *
 * [complete] is false when at least one source `<item>` record was skipped
 * because it could not be decoded: absence from [strikes] is then not
 * evidence that the strike is gone, and repositories must not revoke cached
 * strikes on that basis. Deliberate railway-relevance filtering of
 * successfully decoded records is not a skip. Diagnostics stay
 * adapter-local; only this boolean crosses the provider boundary.
 */
internal data class ParsedMitStrikes(val strikes: List<ProviderStrike>, val complete: Boolean)

internal object MitRssParser {
    fun parse(xml: String): List<ProviderStrike> = parseComplete(xml).strikes

    fun parseComplete(xml: String): ParsedMitStrikes {
        require(xml.contains("<rss", ignoreCase = true) && xml.contains("<channel", ignoreCase = true))
        val items = xml.blocks("item")
        if (items.isEmpty()) return ParsedMitStrikes(emptyList(), true)
        var skipped = 0
        val parsed = items.mapNotNull { block -> item(block) ?: run { skipped++ ; null } }
        require(parsed.isNotEmpty())
        return ParsedMitStrikes(parsed.filter(::railwayRelevant), skipped == 0)
    }

    private fun item(xml: String): ProviderStrike? = runCatching {
        val title = requireNotNull(xml.tag("title")).decoded()
        val description = requireNotNull(xml.tag("description")).decoded().htmlLines()
        val fields = fields(title.replace(" - ", "\n") + "\n" + description)
        val startDate = requireNotNull(fields["data inizio"]?.date())
        val endDate = fields["data fine"]?.date() ?: startDate
        val mode = fields["modalita"]?.takeIf(String::isNotBlank) ?: "Intera giornata"
        val interval = interval(startDate, endDate, mode)
        val guid = xml.tag("guid")?.decoded()?.trim().orEmpty()
        val sourceUrl = guid.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?.replaceFirst("http://", "https://") ?: MitStrikeAdapter.RSS_URL
        val workforce = fields["categoria interessata"]
        val combined = listOf(title, description, fields["note"].orEmpty()).joinToString(" ").normalized()
        ProviderStrike(
            externalId = guid.substringAfterLast('/').takeIf { it.isNotBlank() },
            start = interval.first,
            end = interval.second,
            sector = requireNotNull(fields["settore"]?.takeIf(String::isNotBlank)),
            unions = fields["sindacati"].orEmpty().split('/').map(String::trim).filter(String::isNotBlank),
            workforce = workforce,
            operators = operators(workforce.orEmpty()),
            relevance = relevance(fields["rilevanza"]),
            regions = fields["regione"].listValue("italia", "tutte"),
            provinces = fields["provincia"].listValue("tutte"),
            mode = mode,
            status = when {
                STATUS_REVOKED.any(combined::contains) -> StrikeStatus.REVOKED
                STATUS_MODIFIED.any(combined::contains) -> StrikeStatus.MODIFIED
                else -> StrikeStatus.SCHEDULED
            },
            notes = fields["note"]?.takeIf(String::isNotBlank),
            sourceUrl = sourceUrl,
        )
    }.getOrNull()

    private fun fields(value: String): Map<String, String> = buildMap {
        value.lineSequence().map(String::trim).filter(String::isNotEmpty).forEach { line ->
            val delimiter = line.indexOf(':')
            if (delimiter > 0) {
                put(line.substring(0, delimiter).fieldKey(), line.substring(delimiter + 1).trim())
            }
        }
    }

    private fun interval(startDate: LocalDate, endDate: LocalDate, mode: String): Pair<Instant, Instant> {
        val times = TIME.findAll(mode).map { match ->
            match.groupValues[1].toInt() to match.groupValues[2].toInt()
        }.toList()
        val startTime = times.firstOrNull() ?: (0 to 0)
        val endTime = times.lastOrNull()?.takeIf { times.size > 1 } ?: (23 to 59)
        val start = instant(startDate, startTime)
        var end = instant(endDate, endTime)
        if (end <= start) {
            end = instant(endDate, 23 to 59)
            if (end <= start) end = instant(endDate.plus(kotlinx.datetime.DatePeriod(days = 1)), 0 to 0)
        }
        return start to end
    }

    private fun instant(date: LocalDate, time: Pair<Int, Int>): Instant {
        val (hour, minute) = time
        val actualDate = if (hour == 24) date.plus(kotlinx.datetime.DatePeriod(days = 1)) else date
        return LocalDateTime(actualDate.year, actualDate.month, actualDate.day, hour % 24, minute)
            .toInstant(RailwayTime.zone)
    }

    private fun operators(value: String): List<Operator> = OPERATOR_NAMES
        .filter { it.lowercase() in value.lowercase() }
        .map(::Operator)

    private fun relevance(value: String?): StrikeRelevance = when (value?.normalized()) {
        "locale" -> StrikeRelevance.LOCAL
        "provinciale" -> StrikeRelevance.PROVINCIAL
        "regionale" -> StrikeRelevance.REGIONAL
        "interregionale" -> StrikeRelevance.INTERREGIONAL
        "nazionale" -> StrikeRelevance.NATIONAL
        else -> StrikeRelevance.UNKNOWN
    }

    private fun railwayRelevant(strike: ProviderStrike): Boolean {
        val text = listOf(
            strike.sector,
            strike.mode,
            strike.workforce.orEmpty(),
            strike.operators.joinToString(" ") { it.name },
        ).joinToString(" ").normalized()
        return RAILWAY_MARKERS.any(text::contains)
    }

    private fun String?.listValue(vararg ignored: String): List<String> = this?.split(',', ';')
        ?.map(String::trim)
        ?.filter { value -> value.isNotBlank() && ignored.none { it.equals(value, ignoreCase = true) } }
        .orEmpty()

    private fun String.date(): LocalDate? {
        val parts = trim().split('/')
        if (parts.size != 3) return null
        return runCatching { LocalDate(parts[2].toInt(), parts[1].toInt(), parts[0].toInt()) }.getOrNull()
    }

    private fun String.fieldKey(): String = decoded().normalized()
        .replace('à', 'a').replace('á', 'a')

    private fun String.htmlLines(): String = replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("<[^>]+>"), " ").decoded()

    private fun String.decoded(): String = removePrefix("<![CDATA[").removeSuffix("]]>")
        .replace("&#39;", "'").replace("&apos;", "'").replace("&quot;", "\"")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    private fun String.normalized(): String = trim().lowercase().replace(Regex("\\s+"), " ")

    private fun String.blocks(tag: String): List<String> = Regex(
        "<$tag(?:\\s[^>]*)?>([\\s\\S]*?)</$tag>",
        RegexOption.IGNORE_CASE,
    ).findAll(this).map { it.groupValues[1] }.toList()

    private fun String.tag(tag: String): String? = blocks(tag).firstOrNull()

    private val TIME = Regex("(?<!\\d)([01]?\\d|2[0-4])[.:]([0-5]\\d)(?!\\d)")
    private val STATUS_REVOKED = listOf("revocat", "annullat")
    private val STATUS_MODIFIED = listOf("modificat", "differit", "rinviat", "ridott", "sospes")
    private val RAILWAY_MARKERS = listOf(
        "ferrovi", "treno", "trenitalia", "trenord", "italo", " rfi ", "gruppo fs", "captrain", "db cargo",
    )
    private val OPERATOR_NAMES = listOf("Trenitalia", "Trenord", "Italo", "RFI", "FS Italiane", "Captrain", "DB Cargo")
}
