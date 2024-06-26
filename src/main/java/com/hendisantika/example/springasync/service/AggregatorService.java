package com.hendisantika.example.springasync.service;

import com.hendisantika.example.springasync.domain.Task;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Slf4j
@Service
public class AggregatorService {
    private final OkHttpClient client = new OkHttpClient();

    public void execute(final Task task) {
        log.info("Started task with {} urls", task.getUrls().size());
        task.start();
        for(int i = 0; i < task.getUrls().size(); i++) {
            final int index = i;
            final long time = System.currentTimeMillis();
            String url = task.getUrls().get(i);
            Request req = new Request.Builder().get().url(url).build();

            client.newCall(req).enqueue(new Callback() {
                //                public void onFailure(Request request, IOException e) {
//                    task.fail(index, time, request, e);
//                }
                @Override
                public void onFailure(Call call, IOException e) {
                    task.fail(index, time, call.request(), e);
                    // Handle failure
                    e.printStackTrace();
                }


                //                public void onResponse(Response response) throws IOException {
//                    task.success(index, time, response);
//                }
                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    // Handle success
                    String result = response.body().string();
                    // Process the response data
                    System.out.println(result);
                }
            });
        }
    }
}
