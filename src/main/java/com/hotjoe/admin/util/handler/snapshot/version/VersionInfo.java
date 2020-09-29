package com.hotjoe.admin.util.handler.snapshot.version;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class VersionInfo {


    public String getVersionString() {
        try {
            Properties properties = getGitProperties();

            boolean isDirty = false;

            String gitDirty = properties.getProperty( "git.dirty" );
            if( gitDirty != null )
                isDirty = Boolean.parseBoolean(gitDirty);

            return "built \"" + properties.getProperty("git.build.time") +
                    "\" in branch \"" + properties.getProperty("git.branch") +
                    "\" with short commit id \"" + properties.getProperty("git.commit.id.describe-short") + "\"" +
                    ", isDirty is " + isDirty +
                    " remote url is \"" + properties.getProperty("git.remote.origin.url") + "\"";
        }
        catch( IOException ioe ) {
            return( "can't locate git.properties on the class path");
        }
    }


    private Properties getGitProperties() throws IOException {

        Properties properties = new Properties();

        try (InputStream inputStream = this.getClass().getResourceAsStream("/git.properties")) {
            if (inputStream == null)
                throw new IOException("Can't locate properties file to generate version info");

            properties.load(inputStream);

            return properties;
        }
    }
}
