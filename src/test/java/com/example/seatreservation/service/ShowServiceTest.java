package com.example.seatreservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import com.example.seatreservation.dto.CreateShowRequest;
import com.example.seatreservation.dto.ShowDetailsResponse;
import com.example.seatreservation.dto.ShowResponse;
import com.example.seatreservation.entity.Seat;
import com.example.seatreservation.entity.SeatStatus;
import com.example.seatreservation.entity.Show;
import com.example.seatreservation.exception.ShowNotFoundException;
import com.example.seatreservation.repository.SeatRepository;
import com.example.seatreservation.repository.ShowRepository;

@ExtendWith(MockitoExtension.class)
class ShowServiceTest {

    @Mock
    private ShowRepository showRepository;

    @Mock
    private SeatRepository seatRepository;

    @InjectMocks
    private ShowService showService;

    @Test
    void createsShowAndAvailableSeatsWithDefaultLimit() {
        when(showRepository.save(any(Show.class))).thenAnswer(invocation -> {
            Show show = invocation.getArgument(0);
            setShowId(show, 12L);
            return show;
        });
        when(seatRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        ShowResponse response = showService.createShow(
                new CreateShowRequest("friday-night", List.of("A1", "A2"), 25000L, null));

        ArgumentCaptor<Show> showCaptor = ArgumentCaptor.forClass(Show.class);
        verify(showRepository).save(showCaptor.capture());
        assertThat(showCaptor.getValue().getPerUserLimit()).isEqualTo(4);
        assertThat(showCaptor.getValue().getPricePaise()).isEqualTo(25000L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Seat>> seatsCaptor = ArgumentCaptor.forClass(List.class);
        verify(seatRepository).saveAll(seatsCaptor.capture());
        assertThat(seatsCaptor.getValue())
                .extracting(Seat::getSeatNumber)
                .containsExactly("A1", "A2");
        assertThat(seatsCaptor.getValue())
                .allSatisfy(seat -> {
                    assertThat(seat.getShowId()).isEqualTo(12L);
                    assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
                });
        assertThat(response.perUserLimit()).isEqualTo(4);
        assertThat(response.seats()).containsExactly("A1", "A2");
    }

    @Test
    void createShowRunsInTransaction() throws NoSuchMethodException {
        assertThat(ShowService.class
                .getMethod("createShow", CreateShowRequest.class)
                .getAnnotation(Transactional.class))
                .isNotNull();
    }

    @Test
    void getsShowAndCalculatesSeatCountsFromDatabaseRows() {
        UUID publicId = UUID.randomUUID();
        Show show = org.mockito.Mockito.mock(Show.class);
        when(show.getId()).thenReturn(12L);
        when(show.getPublicId()).thenReturn(publicId);
        when(show.getName()).thenReturn("friday-night");
        when(show.getPricePaise()).thenReturn(25000L);
        when(showRepository.findByPublicId(publicId)).thenReturn(Optional.of(show));
        List<Seat> seats = List.of(
                seat("A1", SeatStatus.CONFIRMED),
                seat("A2", SeatStatus.AVAILABLE),
                seat("A3", SeatStatus.AVAILABLE));
        when(seatRepository.findAllByShowIdOrderBySeatNumberAsc(12L)).thenReturn(seats);

        ShowDetailsResponse response = showService.getShow(publicId);

        assertThat(response.showId()).isEqualTo(publicId);
        assertThat(response.name()).isEqualTo("friday-night");
        assertThat(response.pricePaise()).isEqualTo(25000L);
        assertThat(response.totalSeats()).isEqualTo(3);
        assertThat(response.available()).isEqualTo(2);
        assertThat(response.held()).isZero();
        assertThat(response.confirmed()).isEqualTo(1);
        assertThat(response.available() + response.held() + response.confirmed())
                .isEqualTo(response.totalSeats());
        assertThat(response.seats())
                .extracting(state -> state.seat() + ":" + state.status())
                .containsExactly("A1:CONFIRMED", "A2:AVAILABLE", "A3:AVAILABLE");
        verify(seatRepository).findAllByShowIdOrderBySeatNumberAsc(12L);
    }

    @Test
    void getShowThrowsWhenPublicIdIsUnknown() {
        UUID publicId = UUID.randomUUID();
        when(showRepository.findByPublicId(publicId)).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> showService.getShow(publicId))
                .isInstanceOf(ShowNotFoundException.class);
    }

    @Test
    void getShowUsesReadOnlyTransaction() throws NoSuchMethodException {
        Transactional transactional = ShowService.class
                .getMethod("getShow", UUID.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isTrue();
    }

    private static Seat seat(String seatNumber, SeatStatus status) {
        Seat seat = org.mockito.Mockito.mock(Seat.class);
        when(seat.getSeatNumber()).thenReturn(seatNumber);
        when(seat.getStatus()).thenReturn(status);
        return seat;
    }

    private static void setShowId(Show show, Long id) throws ReflectiveOperationException {
        var idField = Show.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(show, id);
    }
}
