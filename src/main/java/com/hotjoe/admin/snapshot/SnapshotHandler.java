package com.hotjoe.admin.snapshot;


import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CreateSnapshotRequest;
import software.amazon.awssdk.services.ec2.model.CreateSnapshotResponse;
import software.amazon.awssdk.services.ec2.model.DeleteSnapshotRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSnapshotsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSnapshotsResponse;
import software.amazon.awssdk.services.ec2.model.DescribeVolumesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeVolumesResponse;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.Snapshot;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;
import software.amazon.awssdk.services.ec2.model.Volume;
import software.amazon.awssdk.services.ec2.model.VolumeAttachment;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;


/**
 * A class to create a snapshot of an EBS volume.  The expected input is a JSON string, looking something like:
 * <pre>
 * {
 *     "volumeId": "vol-0c4077e79d2c5034d",
 *     "description":"Jenkins Snapshot",
 *     "name": "jenkins-snapshot",
 *     "numSnapshotsToKeep", "10
 * }
 * </pre>
 * <p>
 * Note that the numSnapshotsToKeep value is optional.  If it exists it will override the environment variable below.
 * This allows you to have multiple environments with, for example, your dev environment only keeping a few days of
 * backups and your production keeping more.
 * <br />
 * Two environment variables can optionally be set for the Lambda:
 * <ol>
 *     <li><b>REGION</b> - allows you to override the region that the volume and snapshot live in.  Defaults
 *     to the same region that the Lambda is running in.</li>
 *     <li><b>NUM_SNAPSHOTS_TO_KEEP</b> - an integer number of the number of old snapshots to keep.  Defaults
 *     to 10.  Again, if a value is provided in the JSON input then it will take precedence.</li>
 * </ol>
 */
public class SnapshotHandler implements RequestStreamHandler {
    private static final int NUM_SNAPSHOTS_TO_KEEP_DEFAULT = 10;
    private final ObjectMapper objectMapper = new ObjectMapper();


    /**
     * This is the method called by the AWS Lambda service.
     *
     * @param inputStream  provided by the Lambda service, this is a stream of data provided to the Lambda function
     * @param outputStream provided by the Lambda service, this is a stream of data that can return values.  It is
     *                     unused in this method as there is no output.
     * @param context      the Lambda context that this method was called with.
     */
    @Override
    public void handleRequest(final InputStream inputStream, final OutputStream outputStream, final Context context) {
        LambdaLogger lambdaLogger = context.getLogger();

        int snapshotsToKeep = NUM_SNAPSHOTS_TO_KEEP_DEFAULT;

        JsonNode jsonNode;
        try {
            jsonNode = objectMapper.readTree(inputStream);
        }
        catch( IOException ioe ) {
            lambdaLogger.log("unable to read input stream: " + ioe.getMessage() );
            return;
        }

        String description = jsonNode.get("description").asText();
        String volumeId = jsonNode.get("volumeId").asText();
        String name = jsonNode.get("name").asText();
        JsonNode numSnapshotsToKeepNode = jsonNode.get("numSnapshotsToKeep");
        if (numSnapshotsToKeepNode != null) {
            snapshotsToKeep = Integer.parseInt(numSnapshotsToKeepNode.asText());
        } else {
            String snapshotsToKeepString = System.getenv("NUM_SNAPSHOTS_TO_KEEP");
            if (snapshotsToKeepString != null)
                snapshotsToKeep = Integer.parseInt(snapshotsToKeepString);

            lambdaLogger.log("snapshotsToKeep is " + snapshotsToKeep);
        }

        //
        // you can override the region if needed
        //
        Region region;
        String regionEnvVar = System.getenv("REGION");
        if (regionEnvVar != null)
            region = Region.of(regionEnvVar);
        else
            region = Region.of(System.getenv("AWS_REGION"));

        try (Ec2Client ec2Client = Ec2Client.builder().region(region).build()) {
            //
            // get the instance id(s) that this volume is attached to for tagging
            //
            String nextDescribeVolumesRequestToken = null;
            StringBuilder attachedInstanceIds = new StringBuilder();

            do {
                DescribeVolumesRequest describeVolumesRequest = DescribeVolumesRequest.builder().
                        volumeIds(volumeId)
                        .nextToken(nextDescribeVolumesRequestToken)
                        .build();

                DescribeVolumesResponse describeVolumesResponse = ec2Client.describeVolumes(describeVolumesRequest);

                for (Volume volume : describeVolumesResponse.volumes()) {
                    List<VolumeAttachment> attachments = volume.attachments();

                    for (VolumeAttachment nextVolumeAttachment : attachments) {
                        attachedInstanceIds.append(nextVolumeAttachment.instanceId());
                        attachedInstanceIds.append(",");
                    }
                }

                nextDescribeVolumesRequestToken = describeVolumesResponse.nextToken();

            } while (nextDescribeVolumesRequestToken != null);

            //
            // create the snapshot
            //
            CreateSnapshotRequest createSnapshotRequest = CreateSnapshotRequest.builder()
                    .description(description)
                    .volumeId(volumeId)
                    .tagSpecifications(TagSpecification.builder().tags(
                                    Tag.builder().key("Name").value(name).build(),
                                    Tag.builder().key("VolumeID").value(volumeId).build(),
                                    Tag.builder().key("Description").value(description).build(),
                                    Tag.builder().key("InstanceIDs").value(attachedInstanceIds.substring(0, attachedInstanceIds.length() - 1)).build(),
                                    Tag.builder().key("Recovery Point").value(DateTimeFormatter.ISO_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC))).build())
                            .resourceType(ResourceType.SNAPSHOT).build())
                    .build();

            CreateSnapshotResponse createSnapshotResponse = ec2Client.createSnapshot(createSnapshotRequest);

            lambdaLogger.log("created snapshot request, snapshot id is \"" + createSnapshotResponse.snapshotId() + "\"");

            //
            // now, do we have anything to clean up?
            //
            Filter filter = Filter.builder().name("volume-id").values(volumeId).build();

            List<Snapshot> snapshots = new ArrayList<>();

            String nextDescribeSnapshotsRequestToken = null;
            do {
                DescribeSnapshotsRequest describeSnapshotsRequest = DescribeSnapshotsRequest.builder()
                        .filters(filter)
                        .nextToken(nextDescribeSnapshotsRequestToken)
                        .build();

                DescribeSnapshotsResponse describeSnapshotsResponse = ec2Client.describeSnapshots(describeSnapshotsRequest);

                snapshots.addAll(describeSnapshotsResponse.snapshots());

                nextDescribeSnapshotsRequestToken = describeSnapshotsResponse.nextToken();

            } while (nextDescribeSnapshotsRequestToken != null);

            snapshots.sort(Comparator.comparing(Snapshot::startTime));

            lambdaLogger.log("found " + snapshots.size() + " existing snapshots for volume id " + volumeId);

            if (snapshotsToKeep >= snapshots.size()) {
                lambdaLogger.log("we want to keep " + snapshotsToKeep + " snapshots but only have " +
                        snapshots.size() + " available.  we're done");
                return;
            }

            int numSnapshotsToRemove = snapshots.size() - snapshotsToKeep;

            List<Snapshot> snapshotsToRemove = snapshots.subList(0, numSnapshotsToRemove);

            lambdaLogger.log("removing " + numSnapshotsToRemove + " old snapshot" + (numSnapshotsToRemove > 1 ? "s" : ""));

            for (Snapshot nextRemovedSnapshot : snapshotsToRemove) {
                try {
                    ec2Client.deleteSnapshot(DeleteSnapshotRequest.builder().snapshotId(nextRemovedSnapshot.snapshotId()).build());
                } catch (Ec2Exception ec2Exception) {
                    lambdaLogger.log("error removing snapshot id " + nextRemovedSnapshot.snapshotId() +
                            " - is it in use?  it will be skipped.  error is " + ec2Exception.getMessage());
                }
            }

            lambdaLogger.log("done with run, remaining time in ms is " + context.getRemainingTimeInMillis());
        } catch (Exception exception) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            exception.printStackTrace(pw);

            lambdaLogger.log("overall exception:\n" + sw);
        }
    }
}
