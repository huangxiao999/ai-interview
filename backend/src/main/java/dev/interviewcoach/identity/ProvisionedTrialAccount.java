package dev.interviewcoach.identity;

import java.util.UUID;

record ProvisionedTrialAccount(UUID accountId, String initialPassword) {

    @Override
    public String toString() {
        return "ProvisionedTrialAccount[accountId=" + accountId + ", initialPassword=<redacted>]";
    }
}
