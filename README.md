# README #

An [AWS Lambda](https://aws.amazon.com/lambda/) function to handle creating and maintaining EBS snapshots
of a volume.  A configurable number of old snapshots can be kept.

The input to the Lambda is likely to come from Eventbridge rules but that is not a requirement.  The
input is a very simple JSON object:

```json
{
    "volumeId": "vol-0c4077e123456789",
    "description":"My EC2 Snapshot",
    "name": "my-ec2-snapshot",
    "numSnapshotsToKeep": "10"
}
```

The parameters are:
* **volumeId** - the volume id of the EBS volume that you want to take a snapshot of
* **description** - the description to the snapshot
* **name** - the name for the snapshot
* **numSnapshotsToKeep** - an optional field that specifies the number of snapshots to keep for this volume id.
If it exists it will override the environment variable below.  This allows you to have multiple environments with,
for example, your dev environment only keeping a few days of backups and your production keeping more.

### Setup ###

On the build side this is a normal Java Maven project.  Java 17 is used to compile.  Build with:

`mvn clean package`

to get a `jar` file that can be deployed to Lambda.  Note that this `pom.xml` has been intentionally updated to remove
warnings related to the new code in Maven 3.9 and above.  As of this writing Maven 4.x is still in Alpha, so this
code has not been tested on that.

### Profiles, or choosing your packaging tool ###
The deployment package is `target/snapshotlambda-1.0.jar`.  This is the default when using the `shade` profile.
That profile uses the AWS recommended [Apache Maven Shade Plugin](https://maven.apache.org/plugins/maven-shade-plugin/).
When you run this you will get multiple warnings about file name collisions.  All of these are for files in `META-INF`
like LICENSE and other files that are not likely to be an issue with a collision.

The warnings end with:

<blockquote>
[WARNING] maven-shade-plugin has detected that some files are<br />
[WARNING] present in two or more JARs. When this happens, only one<br />
[WARNING] single version of the file is copied to the uber jar.<br />
[WARNING] Usually this is not harmful and you can skip these warnings,<br />
[WARNING] otherwise try to manually exclude artifacts based on<br />
[WARNING] mvn dependency:tree -Ddetail=true and the above output.<br />
[WARNING] See https://maven.apache.org/plugins/maven-shade-plugin/<br />
</blockquote>

I'll admit I'm not a fan of "trust me, it's likely ok" type of warnings.  I haven't spent the time to update
`pom.xml` to exclude the duplicate files at this point.

Another option for the build is using code from [Microlam](https://microlam.io/).  I have seen debates about the
use of the Maven shade plugin and, while I'm not positive I agree with all the negatives, the warnings are a bit
concerning.  So if you compile with:

`mvn clean package -Pmicrolam`

you'll get the file `target/snapshotlambda-1.0-aws-lambda.zip` that can be deployed to Lambda the same way as the
`jar` version used in the default / shade profile.  The file is slightly smaller when using the Microlam packaging.

### Handler ###
Regardless of how you build the output, the handler that will be needed in Lambda is
`com.hotjoe.admin.snapshot.SnapshotHandler::handleRequest` when deploying.

### Environment Variables ###
Two environment variables can optionally be set for the Lambda:

1) **REGION** - allows you to override the region that the volume and snapshot live in.  Defaults
                to the same region that the Lambda is running in.
2) **NUM_SNAPSHOTS_TO_KEEP** - an integer number of the number of old snapshots to keep.  Defaults to 10.

### Snapshot Tags ###
All snapshots are tagged as
[AWS recommends](https://docs.aws.amazon.com/prescriptive-guidance/latest/backup-recovery/ec2-backup.html#tagging) with
the following tags:
* **Name** - the name for the snapshot, as defined in the incoming JSON
* **VolumeID** - the volume id of the EBS volume that you want to take a snapshot of
* **Description** - the description to the snapshot as defined in the incoming JSON.  This value is also part of the
Description field in the snapshot.
* **InstanceID** - a comma separated list of instance id's that this volume is attached to
* **Recovery Point** - the time stamp (UTC) when this volume snapshot was started

### Lambda Runtime Permissions ###
The most likely error you'll run across is that the Lambda does not have permission to create the snapshot from the
volume.  While you can assign the `AmazonEC2FullAccess` policy, this is vastly too much power.  I haven't come up with
the perfect set of permissions but for debugging, run the Lambda manually to make sure it has the correct permissions 
for the role that the Lambda is assigned to.

