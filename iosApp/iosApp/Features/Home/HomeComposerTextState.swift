/// One composer text field's editing rule (T8.5 corrective). The TextField
/// writes through `userEdited`, which forwards only text the shared truth
/// does not already hold; shared emissions apply through `applyShared`,
/// which assigns the local copy directly and never invokes the user-edit
/// action. This keeps resolved stations alive across suggestion selection,
/// swap, prefill and returning to Home, while free typing still clears the
/// resolved endpoint inside JourneySearchComponent as intended.
struct HomeComposerTextState {
    private(set) var local: String

    init(local: String = "") {
        self.local = local
    }

    /// Returns the text to forward to `stationText`, or nil when this change
    /// originated from shared state and must not be fed back.
    mutating func userEdited(_ new: String, shared: String) -> String? {
        local = new
        return new == shared ? nil : new
    }

    mutating func applyShared(_ shared: String) {
        local = shared
    }
}
