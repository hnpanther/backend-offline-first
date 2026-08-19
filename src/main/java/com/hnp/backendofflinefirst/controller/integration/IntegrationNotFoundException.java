package com.hnp.backendofflinefirst.controller.integration;

/**
 * No finished log sheet carries the requested id.
 *
 * <p>Its own type rather than {@code IllegalArgumentException} because the two must produce
 * different status codes on this API — 404 against 400 — and the shared
 * {@code ApiExceptionHandler} maps every {@code IllegalArgumentException} to 400. A caller
 * distinguishes "you asked for something that is not there" from "you asked wrongly" by the
 * status code, and a 400 for a missing id would send an integrator hunting through their own
 * request parameters for a fault that is not in them.
 */
public class IntegrationNotFoundException extends RuntimeException {

    public IntegrationNotFoundException(String message) {
        super(message);
    }
}
