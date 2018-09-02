package com.hendisantika.example.springasync.controller;

import com.hendisantika.example.springasync.domain.AggregateResponse;
import com.hendisantika.example.springasync.domain.ApiRequest;
import com.hendisantika.example.springasync.domain.Task;
import com.hendisantika.example.springasync.service.AggregatorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;

@RestController
public class AggregatorController {
    private final AggregatorService service;

    @Autowired
    public AggregatorController(final AggregatorService service) {
        this.service = service;
    }

    @RequestMapping(value = "/aggregate", method = RequestMethod.POST)
    @ResponseBody
    public DeferredResult<ResponseEntity<AggregateResponse>> call(@RequestBody final ApiRequest request) {

        DeferredResult<ResponseEntity<AggregateResponse>> result = new DeferredResult<>();
        Task task = new Task(result, request.getUrls());
        service.execute(task);

        return result;
    }
}
