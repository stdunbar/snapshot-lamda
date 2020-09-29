# README #

A simple [AWS Lambda](https://aws.amazon.com/lambda/) function to handle creating an EBS snapshot
of a volume and to remove "old" snapshots.

The input to the Lambda is likely to come from CloudWatch but that is not a requirement.  The
input is a very simple JSON object:

```json
{
    "volumeId": "vol-0c4077e123456789",
    "description":"My EC2 Snapshot",
    "name": "my-ec2-snapshot"
}
```

The parameters are:
* **volumeId** - the volume id of the EBS volume that you want to take a snapshot of
* **description** - the description to the snapshot
* **name** - the name for the snapshot

### Setup ###

On the build side this is a simple Maven project.  Simply do a:

`mvn clean package`

to get a Jar file that can be deployed to Lambda.  If you've cloned this repository and run
the build then you will need to deploy `target/snapshotlambda-1.0.jar` to Lambda.
The handler that will be needed in Lambda is `com.hotjoe.admin.snapshot.SnapshotHandler::handleRequest`
with the code I have here.


### Environment Variables ###
Two environment variables can optionally be set for the Lambda:

1) **REGION** - allows you to override the region that the volume and snapshot live in.  Defaults
                to the same region that the Lambda is running in.
2) **NUM_SNAPSHOTS_TO_KEEP** - an integer number of the number of old snapshots to keep.  Defaults to 10
