package it.danielebufarini.trenify.provider.journey

// Reduced wire shapes observed from public guest searches on 2026-09-05.
// Session/booking/sell keys and fares are deliberately omitted; fixture identifiers are synthetic.
internal val trenitaliaFixtureV1 = """
{"solutions":[{"solution":{"id":"discard-me","nodes":[
{"origin":"Roma Termini","destination":"Milano Centrale","departureTime":"2026-09-06T08:05:00.000+02:00",
"arrivalTime":"2026-09-06T11:50:00.000+02:00","bdoOrigin":"DO-NOT-EXPORT",
"train":{"name":"9516","trainCategory":"Frecciarossa","acronym":"FR","futureField":"ignored"}}
]},"price":{"amount":102}}]}
""".trimIndent()

/** Synthetic regression topology matching the observed intermediate REG 2815 journey shape. */
internal val trenitaliaRegional2815Fixture = """
{"solutions":[{"solution":{"nodes":[
{"origin":"Monza","destination":"Milano Centrale","departureTime":"2026-09-18T08:27:00.000+02:00",
"arrivalTime":"2026-09-18T08:43:00.000+02:00",
"train":{"name":"2815","trainCategory":"Regionale","acronym":"REG"}}
]}}]}
""".trimIndent()

internal val italoFixtureV1 = """
{"bookingId":"discard-me","trips":[{"direction":"forward","travelSolutions":[{"journeys":[
{"serviceProvider":"ITALO","sequence":1,"segments":[{"departureStation":"RMT","arrivalStation":"MC_",
"std":"2026-09-06T06:35:00","sta":"2026-09-06T10:20:00","trainNumber":"9908","carrierCode":"VF","equipmentType":"EVO"}]}]
}]}]}
""".trimIndent()

// Reduced live guest-search response observed on 2026-10-08. Fare/sell/session
// fields removed. No platform was supplied; this is not a simulated RFI feed.
internal val italo9931Fixture = """
{"trips":[{"direction":"forward","travelSolutions":[{"journeys":[
{"serviceProvider":"ITALO","sequence":1,"segments":[{"departureStation":"MC_","arrivalStation":"RMT",
"std":"2026-10-08T11:40:00","sta":"2026-10-08T15:19:00","trainNumber":"9931","carrierCode":"VF"}]}]
}]}]}
""".trimIndent()
