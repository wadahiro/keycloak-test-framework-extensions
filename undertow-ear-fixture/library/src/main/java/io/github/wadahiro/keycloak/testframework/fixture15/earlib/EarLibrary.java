package io.github.wadahiro.keycloak.testframework.fixture15.earlib;

/** A class that only the EAR's library directory provides. */
public final class EarLibrary {
    private EarLibrary() {
    }

    public static String describe() {
        return "ear-library-loaded";
    }
}
