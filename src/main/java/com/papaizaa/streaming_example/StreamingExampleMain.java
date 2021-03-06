package com.papaizaa.streaming_example;

import com.papaizaa.streaming_example.generated_pb.Events;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.extensions.protobuf.ProtoCoder;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubIO;
import org.apache.beam.sdk.options.*;
import org.apache.beam.sdk.transforms.Flatten;
import org.apache.beam.sdk.transforms.GroupByKey;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.windowing.*;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionList;
import org.joda.time.Duration;


import static org.apache.beam.sdk.transforms.windowing.AfterProcessingTime.pastFirstElementInPane;

public class StreamingExampleMain {

    public static final int ALLOWED_LATENESS_SEC = 0;
    public static final int SESSION_WINDOW_GAP_DURATION = 10;

    public static void main(String[] args) {
        StreamingPipelineOptions options =
                PipelineOptionsFactory.fromArgs(args).withValidation().as(StreamingPipelineOptions.class);

        Pipeline pipeline = Pipeline.create(options);
        pipeline.getCoderRegistry().registerCoderForClass(Events.Event.class, ProtoCoder.of(Events.Event.class));

        // Trigger on first element in window, for every new element and when the window closes
        Trigger trigger = Repeatedly.forever(pastFirstElementInPane()
                .orFinally(AfterWatermark.pastEndOfWindow()));

        // If no activity for `SESSION_WINDOW_GAP_DURATION` minutes, trigger a window closed event
        Window<KV<String, Events.Event>> sessionWindow =
                Window.<KV<String, Events.Event>>into(Sessions.withGapDuration(
                        Duration.standardMinutes(SESSION_WINDOW_GAP_DURATION)))
                        .triggering(trigger)
                        .withAllowedLateness(Duration.standardSeconds(ALLOWED_LATENESS_SEC))
                        .accumulatingFiredPanes();

        PCollection<Events.Event> books = pipeline.apply("Read events",
                PubsubIO.readProtos(Events.Event.class).fromSubscription(options.getBookSubscriptionName()));

        PCollection<Events.Event> groceries = pipeline.apply("Read events",
                PubsubIO.readProtos(Events.Event.class).fromSubscription(options.getGrocerySubscriptionName()));

        // Flatten two PubSub streams into one collection
        PCollection<Events.Event> events = PCollectionList.of(books).and(groceries).apply(Flatten.pCollections());

        PCollection<KV<String, Events.Event>> tuple =
                events.apply("Key Value Split", ParDo.of(new KeyValueSplit.Parse()));

        tuple.apply("Apply to Session Window", sessionWindow)
                .apply("Group By Key", GroupByKey.create())
                .apply("Parse And Format Pub Sub Message", ParDo.of(new CombineEvents.Combine()))
                .apply("Send to Backend", PubsubIO.writeProtos(Events.Output.class).to(options.getOutputTopic()));


        pipeline.run();
    }

    public interface StreamingPipelineOptions extends PipelineOptions {

        @Description("pubsub books subscription name")
        @Default.String("")
        String getBookSubscriptionName();

        void setBookSubscriptionName(String value);

        @Description("pubsub groceries subscription name")
        @Default.String("")
        String getGrocerySubscriptionName();

        @Description("PubSub Topic to write the output to")
        @Default.String("")
        ValueProvider<String> getOutputTopic();

        void setOutputTopic(String value);

    }

}
