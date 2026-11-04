package com.markettec.users.application.session;

/** Trusted adapter must prove an explicit renewal or allowlisted server activity. */
public interface SessionActionAuthorization {
    void requireRenewal(Session session);
    void requireAdmissibleActivity(Session session);
}
