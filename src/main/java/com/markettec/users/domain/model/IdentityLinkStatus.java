package com.markettec.users.domain.model;

/** A link authorizes login only when explicitly active. */
public enum IdentityLinkStatus { PENDING, ACTIVE, DISABLED, REVOKED, CONFLICT }
