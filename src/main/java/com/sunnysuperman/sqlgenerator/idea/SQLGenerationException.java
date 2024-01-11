package com.sunnysuperman.sqlgenerator.idea;

public class SQLGenerationException extends Exception {

    public SQLGenerationException(String message) {
        super(message);
    }

    public SQLGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
