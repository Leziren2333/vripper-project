package tn.mnlr.vripper.web.restendpoints;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tn.mnlr.vripper.jpa.repositories.IEventRepository;

@Slf4j
@RestController
@CrossOrigin(value = "*")
public class EventLogRestEndpoint {

    private final IEventRepository eventRepository;

    public EventLogRestEndpoint(IEventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    @GetMapping("/events/clear")
    @ResponseStatus(value = HttpStatus.OK)
    public void clear() {
        eventRepository.deleteAll();
    }
}
