package com.geto.jido.server;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * Marker for tests that need the full application context wired against the
 * jido_test database (see src/test/resources/application-test.yml).
 */
@SpringBootTest
public abstract class IntegrationTest {
}
