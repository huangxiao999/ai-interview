package dev.interviewcoach.identity;

import java.util.UUID;

record ResetTrialAccount(UUID accountId, long credentialVersion, String newPassword) {

    @Override
    public String toString() {
        return "ResetTrialAccount[accountId=" + accountId + ", credentialVersion="
                + credentialVersion + ", newPassword=<redacted>]";
    }
}
