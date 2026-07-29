package org.wodichka.packcontrol.publisher;

public final class PublisherException extends Exception {
    public PublisherException(String message) {
        super(message);
    }

    public PublisherException(String message, Throwable cause) {
        super(message, cause);
    }
}
