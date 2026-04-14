package com.davidneto.homepage.webdav;

import io.milton.http.ResourceFactory;
import io.milton.http.exceptions.BadRequestException;
import io.milton.http.exceptions.NotAuthorizedException;
import io.milton.resource.Resource;
import org.springframework.stereotype.Component;

/**
 * Stub ResourceFactory — always returns null (no resources served yet).
 * Task 11 will implement per-user filesystem routing.
 */
@Component
public class PerUserFileResourceFactory implements ResourceFactory {

    @Override
    public Resource getResource(String host, String path)
            throws NotAuthorizedException, BadRequestException {
        return null; // stub; Task 11 fills in.
    }
}
