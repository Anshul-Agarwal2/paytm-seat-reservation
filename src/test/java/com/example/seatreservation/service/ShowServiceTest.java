package com.example.seatreservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import com.example.seatreservation.dto.CreateShowRequest;
import com.example.seatreservation.dto.ShowResponse;
import com.example.seatreservation.entity.Seat;
import com.example.seatreservation.entity.SeatStatus;
import com.example.seatreservation.entity.Show;
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

    private static void setShowId(Show show, Long id) throws ReflectiveOperationException {
        var idField = Show.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(show, id);
    }
}
