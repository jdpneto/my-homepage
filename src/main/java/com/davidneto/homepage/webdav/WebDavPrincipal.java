package com.davidneto.homepage.webdav;

import java.security.Principal;

public record WebDavPrincipal(String username) implements Principal {
    @Override public String getName() { return username; }
}
