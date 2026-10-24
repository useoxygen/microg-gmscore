package org.microg.gms.common;

import java.io.IOException;

public class NotOkayException extends IOException {
    private int httpStatus;

    public NotOkayException(String message, int httpStatus) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() { return httpStatus; }

    public NotOkayException() {
    }

    public NotOkayException(String message) {
        super(message);
    }

    public NotOkayException(String message, Throwable cause) {
        super(message, cause);
    }

    public NotOkayException(Throwable cause) {
        super(cause);
    }
}
