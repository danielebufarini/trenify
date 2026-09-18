package it.danielebufarini.trenify.provider.viaggiatreno

// v1: representative, minimized wire shapes checked against the HTTPS service on 2026-09-05.
// Synthetic cancellation/drift variants are explicitly constructed by the contract tests.
internal object FixturesV1 {
    const val stations = "ROMA TERMINI|S08409\nROMA TIBURTINA|S08217\n"
    const val candidates = "2493 - MILANO CENTRALE - 05/09/26|2493-S01700-1788559200000\n"
    const val board = """[{"numeroTreno":2493,"codOrigine":"S01700","origine":"MILANO CENTRALE","destinazione":"PESCARA","dataPartenzaTreno":1788559200000,"orarioPartenza":1788582600000,"orarioArrivo":1788609900000,"nonPartito":true,"provvedimento":0,"binarioProgrammatoPartenzaDescrizione":"23","binarioEffettivoPartenzaDescrizione":"19","binarioProgrammatoArrivoDescrizione":"2","binarioEffettivoArrivoDescrizione":"3","futureField":true}]"""
    const val detail = """{"numeroTreno":2493,"idOrigine":"S01700","origine":"MILANO CENTRALE","destinazione":"PESCARA","dataPartenzaTreno":1788559200000,"tipoTreno":"PG","provvedimento":0,"circolante":true,"oraUltimoRilevamento":1788582780000,"ritardo":3,"fermate":[{"id":"S01700","stazione":"MILANO CENTRALE","partenza_teorica":1788582600000,"partenzaReale":1788582780000,"binarioProgrammatoPartenzaDescrizione":"23","binarioEffettivoPartenzaDescrizione":"19","actualFermataType":1}],"extra":{"unknown":true}}"""

    // Synthetic regression topology for an operator-unknown Journey leg that
    // boards part-way through a source-identified run. Identity uses planned
    // times; actual times carry the independent +7-minute realtime state.
    const val detailRegional2815 = """{"numeroTreno":2815,"idOrigine":"S01440","origine":"TIRANO","destinazione":"MILANO CENTRALE","dataPartenzaTreno":1789682400000,"categoria":"REG","tipoTreno":"PG","provvedimento":0,"circolante":true,"codiceCliente":18,"orarioPartenza":1789704600000,"orarioArrivo":1789713780000,"ritardo":7,"fermate":[{"id":"S01440","stazione":"TIRANO","partenza_teorica":1789704600000,"partenzaReale":1789705020000,"actualFermataType":1},{"id":"S01322","stazione":"MONZA","arrivo_teorico":1789712700000,"arrivoReale":1789713120000,"partenza_teorica":1789712820000,"partenzaReale":1789713240000,"actualFermataType":1},{"id":"S01700","stazione":"MILANO CENTRALE","arrivo_teorico":1789713780000,"arrivoReale":1789714200000,"actualFermataType":0}]}"""

    // Deterministic reproduction of REG 2823 on 2026-09-18. The source puts
    // platform values on each `fermate[]` record, not on the train root:
    // Monza has the observed 12:26/12:27 schedule, +6-minute actual times,
    // expected departure platform 5 and an independent actual platform 7.
    // Tirano and Milano deliberately carry different values so consumers
    // cannot accidentally promote Monza's platform to a run-global field.
    const val detailRegional2823 = """{"numeroTreno":2823,"idOrigine":"S01440","origine":"TIRANO","destinazione":"MILANO CENTRALE","dataPartenzaTreno":1789682400000,"categoria":"REG","tipoTreno":"PG","provvedimento":0,"circolante":true,"codiceCliente":18,"orarioPartenza":1789718880000,"orarioArrivo":1789728180000,"ritardo":6,"oraUltimoRilevamento":1789727580000,"fermate":[{"id":"S01440","stazione":"TIRANO","partenza_teorica":1789718880000,"partenzaReale":1789718970000,"binarioProgrammatoPartenzaDescrizione":"1","binarioEffettivoPartenzaDescrizione":"1","actualFermataType":1},{"id":"S01322","stazione":"MONZA","arrivo_teorico":1789727160000,"arrivoReale":1789727520000,"partenza_teorica":1789727220000,"partenzaReale":1789727580000,"binarioProgrammatoArrivoDescrizione":"5","binarioProgrammatoPartenzaDescrizione":"5","binarioEffettivoArrivoDescrizione":"7","binarioEffettivoPartenzaDescrizione":"7","actualFermataType":1},{"id":"S01700","stazione":"MILANO CENTRALE","arrivo_teorico":1789728180000,"binarioProgrammatoArrivoDescrizione":"9","actualFermataType":0}]}"""

    // T7.10 full detail: REG 8412 Milano Centrale -> Roma Termini, service date
    // 2026-09-05 (dataPartenzaTreno 1788559200000). Terminals: origin departure
    // 06:30 Rome (1788582600000), destination arrival 09:40 Rome (1788594000000).
    // Milano + Bologna passed (type 1 with actuals), Firenze scheduled future,
    // Roma scheduled future; last detection at Bologna 07:38 Rome (1788586680000).
    const val detailFull = """{"numeroTreno":8412,"idOrigine":"S01700","origine":"MILANO CENTRALE","destinazione":"ROMA TERMINI","dataPartenzaTreno":1788559200000,"categoria":"REG","tipoTreno":"PG","provvedimento":0,"circolante":true,"orarioPartenza":1788582600000,"orarioArrivo":1788594000000,"stazioneUltimoRilevamento":"BOLOGNA CENTRALE","oraUltimoRilevamento":1788586680000,"ritardo":3,"fermate":[{"id":"S01700","stazione":"MILANO CENTRALE","partenza_teorica":1788582600000,"partenzaReale":1788582780000,"binarioProgrammatoPartenzaDescrizione":"4","binarioEffettivoPartenzaDescrizione":"4","actualFermataType":1},{"id":"S10001","stazione":"BOLOGNA CENTRALE","arrivo_teorico":1788586200000,"arrivoReale":1788586380000,"partenza_teorica":1788586500000,"partenzaReale":1788586680000,"actualFermataType":1},{"id":"S10002","stazione":"FIRENZE S.M.N.","arrivo_teorico":1788590100000,"partenza_teorica":1788590400000,"actualFermataType":0},{"id":"S08409","stazione":"ROMA TERMINI","arrivo_teorico":1788594000000,"actualFermataType":0}]}"""

    // T7.10 departures board at Bologna: the station event (07:35 Rome,
    // 1788586500000) differs from the origin terminal departure (06:30 Rome).
    const val boardBolognaDepartures = """[{"numeroTreno":8412,"codOrigine":"S01700","origine":"MILANO CENTRALE","destinazione":"ROMA TERMINI","dataPartenzaTreno":1788559200000,"categoria":"REG","categoriaDescrizione":"REG","orarioPartenza":1788586500000,"provvedimento":0,"circolante":true,"ritardo":3,"binarioProgrammatoPartenzaDescrizione":"1","binarioEffettivoPartenzaDescrizione":"1"}]"""
    // T7.10 arrivals board at Bologna: the station event (07:30 Rome,
    // 1788586200000) with a Frecce category carried only by categoriaDescrizione.
    const val boardBolognaArrivals = """[{"numeroTreno":9624,"codOrigine":"S01700","origine":"MILANO CENTRALE","destinazione":"ROMA TERMINI","dataPartenzaTreno":1788559200000,"categoria":"","categoriaDescrizione":" FR","orarioArrivo":1788586200000,"provvedimento":0,"circolante":true,"ritardo":5,"binarioProgrammatoArrivoDescrizione":"2","binarioEffettivoArrivoDescrizione":"2"}]"""

    // T7.10: the type marker governs stop status — a type-0 stop stays scheduled
    // even with actual timestamps present, and "--" with no observation time
    // yields no position.
    const val detailScheduledTypeWithActuals = """{"numeroTreno":1201,"idOrigine":"S08409","origine":"ROMA TERMINI","destinazione":"FIRENZE S.M.N.","dataPartenzaTreno":1788559200000,"categoria":"REG","tipoTreno":"PG","provvedimento":0,"circolante":true,"orarioPartenza":1788582600000,"orarioArrivo":1788586200000,"stazioneUltimoRilevamento":"--","fermate":[{"id":"S08409","stazione":"ROMA TERMINI","partenza_teorica":1788582600000,"partenzaReale":1788582780000,"actualFermataType":0},{"id":"S10002","stazione":"FIRENZE S.M.N.","arrivo_teorico":1788586200000,"actualFermataType":0}]}"""

    // T7.10: fully cancelled train; the source unknown-position marker must not
    // become a station and the missing observation time must not be fabricated.
    const val detailCancelled = """{"numeroTreno":3307,"idOrigine":"S08409","origine":"ROMA TERMINI","destinazione":"PESCARA","dataPartenzaTreno":1788559200000,"categoria":"REG","tipoTreno":"ST","provvedimento":1,"stazioneUltimoRilevamento":"--","fermate":[{"id":"S08409","stazione":"ROMA TERMINI","partenza_teorica":1788582600000,"actualFermataType":3}]}"""

    // T7.10 composite category: the display designation "EC FR" is stronger
    // evidence than the coarse "EC" and must not be reduced to plain EC.
    // compNumeroTreno is a display derivative and carries no independent
    // semantics for the mapping.
    const val detailComposite = """{"numeroTreno":2251,"idOrigine":"S01700","origine":"MILANO CENTRALE","destinazione":"ZUERICH HB","dataPartenzaTreno":1788559200000,"categoria":"EC","categoriaDescrizione":"EC FR","compNumeroTreno":"EC FR 2251","tipoProdotto":"0","tipoTreno":"PG","provvedimento":0,"circolante":true,"orarioPartenza":1788582600000,"orarioArrivo":1788594000000,"fermate":[]}"""
    const val boardComposite = """[{"numeroTreno":2251,"codOrigine":"S01700","origine":"MILANO CENTRALE","destinazione":"ZUERICH HB","dataPartenzaTreno":1788559200000,"categoria":"EC","categoriaDescrizione":"EC FR","compNumeroTreno":"EC FR 2251","tipoProdotto":"0","orarioPartenza":1788582600000,"provvedimento":0,"circolante":true,"ritardo":0}]"""

    // T7.10 overnight train across the 2026-10-25 Rome DST transition: departure
    // 23:50 Rome Oct 25 (+01:00, 1792968600000), arrival 01:20 Rome Oct 26
    // (+01:00, 1792974000000). Service date stays 2026-10-25 while the arrival
    // lands on the next local date.
    const val detailOvernightDst = """{"numeroTreno":1963,"idOrigine":"S08409","origine":"ROMA TERMINI","destinazione":"MILANO CENTRALE","dataPartenzaTreno":1792879200000,"categoria":"ICN","tipoTreno":"PG","provvedimento":0,"circolante":true,"orarioPartenza":1792968600000,"orarioArrivo":1792974000000,"stazioneUltimoRilevamento":"FIRENZE S.M.N.","oraUltimoRilevamento":1792970400000,"ritardo":0,"fermate":[{"id":"S08409","stazione":"ROMA TERMINI","partenza_teorica":1792968600000,"partenzaReale":1792968600000,"actualFermataType":1},{"id":"S01700","stazione":"MILANO CENTRALE","arrivo_teorico":1792974000000,"actualFermataType":0}]}"""
}
