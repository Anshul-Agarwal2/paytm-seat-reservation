package com.example.seatreservation.exception;

import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.seatreservation.dto.ApiErrorResponse;
import com.example.seatreservation.metrics.ReservationMetrics;

@RestControllerAdvice
public class ApiExceptionHandler {

    private final ReservationMetrics reservationMetrics;

    public ApiExceptionHandler(ReservationMetrics reservationMetrics) {
        this.reservationMetrics = reservationMetrics;
    }

    @ExceptionHandler(ShowNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiErrorResponse handleShowNotFound(ShowNotFoundException exception) {
        return new ApiErrorResponse("SHOW_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(ReservationNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiErrorResponse handleReservationNotFound(ReservationNotFoundException exception) {
        return new ApiErrorResponse("RESERVATION_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(ReservationOwnershipException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiErrorResponse handleReservationOwnership(ReservationOwnershipException exception) {
        return new ApiErrorResponse("RESERVATION_FORBIDDEN", exception.getMessage());
    }

    @ExceptionHandler(SeatNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiErrorResponse handleSeatNotFound(SeatNotFoundException exception) {
        return new ApiErrorResponse("SEAT_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler({
            SeatAlreadyTakenException.class,
            PerUserLimitExceededException.class,
            IdempotencyConflictException.class,
            ReservationNotCancellableException.class
    })
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiErrorResponse handleReservationConflict(RuntimeException exception) {
        String error;
        if (exception instanceof SeatAlreadyTakenException) {
            error = "SEAT_ALREADY_TAKEN";
            reservationMetrics.recordSeatTaken();
        } else if (exception instanceof PerUserLimitExceededException) {
            error = "PER_USER_LIMIT_EXCEEDED";
            reservationMetrics.recordPerUserLimit();
        } else if (exception instanceof IdempotencyConflictException) {
            error = "IDEMPOTENCY_CONFLICT";
            reservationMetrics.recordIdempotencyConflict();
        } else if (exception instanceof ReservationNotCancellableException) {
            error = "RESERVATION_NOT_CANCELLABLE";
        } else {
            throw new IllegalStateException("Unexpected reservation conflict", exception);
        }
        return new ApiErrorResponse(error, exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiErrorResponse handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .distinct()
                .collect(Collectors.joining("; "));
        if (message.isEmpty()) {
            message = exception.getBindingResult().getGlobalErrors().stream()
                    .map(error -> error.getDefaultMessage())
                    .distinct()
                    .collect(Collectors.joining("; "));
        }
        return new ApiErrorResponse("VALIDATION_ERROR", message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiErrorResponse handleUnreadableRequest() {
        return new ApiErrorResponse("INVALID_REQUEST", "Request body is missing or malformed");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiErrorResponse handleDataIntegrityViolation() {
        return new ApiErrorResponse("DATA_CONFLICT", "The request conflicts with existing data");
    }
}
