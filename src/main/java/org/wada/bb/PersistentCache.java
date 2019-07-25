package org.wada.bb;

import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

import static org.slf4j.LoggerFactory.getLogger;

public class PersistentCache {
    // get a static slf4j logger for the class
    protected static final Logger logger = getLogger(BitbucketCodeReviewV2.class);

    private final String URL;
    private boolean h2Enabled;
    private final boolean DB_USE_TEMP_FOLDER = false;

    private String repositoryName;
    private int pullRequestId;
    private Connection connection;

    public PersistentCache() throws SQLException {
        // get the system temp folder
        URL = "jdbc:h2:" + getDBFolder() + "/" + PersistentCache.class.getName() + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
    }

    private boolean initH2db() {
        logger.info("Using H2 Database in {}", URL);

        boolean h2Enabled = false;
        try (PreparedStatement preparedStatement = connection.prepareStatement("CREATE TABLE IF NOT EXISTS BITBUCKET_COMMENTS(" +
                "REPO_NAME VARCHAR(50) NOT NULL, " +
                "PULL_REQUEST INT NOT NULL, " +
                "COMMENT_ID INT NOT NULL, " +
                "JIRA_ISSUE VARCHAR(75), " +
                "LAST_UPDATED TIMESTAMP null," +
                "PRIMARY KEY (PULL_REQUEST, COMMENT_ID) " +
                " );");) {
            logger.info("Creating BITBUCKET_COMMENTS table if it doesn't exist in DB {}", URL);
            preparedStatement.execute();
            h2Enabled = true;
        } catch (SQLException ex) {
            logger.error("Error using the H2 DB.  Tracking via H2 is disabled; every USERA call/stack trace will be logged. {}", ex);
        }

        return h2Enabled;
    }

    public void open(String repositoryName, int pullRequestId) throws SQLException {
        this.repositoryName = repositoryName;
        this.pullRequestId = pullRequestId;
        connection = DriverManager.getConnection(URL);
        connection.setAutoCommit(true);
        this.h2Enabled = initH2db();

    };


    /**
     * Get system temp folder
     *
     * @return temp folder or "." if an error occurs looking for it
     */
    private String getDBFolder() {
        // use the home folder for the db
        String folderName = "~/nextgen";

        // recalculate to use the temp folder
        if (DB_USE_TEMP_FOLDER) {
            try {
                //create a temp file
                File temp = File.createTempFile("temp-file-name", ".tmp");

                //Get tempropary file path
                String absolutePath = temp.getAbsolutePath();
                String tempFilePath = absolutePath.substring(0, absolutePath.lastIndexOf(File.separator));
                return tempFilePath;

            } catch (IOException e) {
                e.printStackTrace();
                folderName = "./";
            }
        }

        return folderName;
    }


    /**
     * Add this commentId to the cache
     * @param commentId
     * @param jiraIssue
     * @throws SQLException
     */
    public void put(int commentId, String jiraIssue) throws SQLException {
        try (PreparedStatement preparedStatement = connection.prepareStatement("INSERT INTO BITBUCKET_COMMENTS (REPO_NAME, PULL_REQUEST, COMMENT_ID, JIRA_ISSUE, LAST_UPDATED ) VALUES(?,?,?,?,?);")) {
            preparedStatement.setString(1, repositoryName);
            preparedStatement.setInt(2, pullRequestId);
            preparedStatement.setInt(3, commentId);
            preparedStatement.setString(4, jiraIssue);
            preparedStatement.setTimestamp(5, Timestamp.from(Instant.now()));
            preparedStatement.execute();

        } catch (SQLException e) {
            logger.error("SQL Exception encountered.  Skipping this comment id: {}", commentId, e);
            throw e;
        }
    }


    /**
     * Gets the associated Jira ticket for this commentId
     * @param commentId
     * @return
     */
    public String get(int commentId) {
        try (PreparedStatement preparedStatement = connection.prepareStatement("SELECT JIRA_ISSUE from BITBUCKET_COMMENTS where REPO_NAME = ? AND PULL_REQUEST = ? AND COMMENT_ID = ?");) {
            preparedStatement.setString(1, repositoryName);
            preparedStatement.setInt(2, pullRequestId);
            preparedStatement.setInt(3, commentId);
            ResultSet resultSet = preparedStatement.executeQuery();
            return resultSet.next() ? resultSet.getString(1) : null;
        } catch (SQLException ex) {
            logger.error("{}", ex);
        }

        // error occurred
        return null;
    }


    /**
     * Close the DB connection
     * @throws SQLException
     */
    public void close() throws SQLException {
        if( connection != null && !connection.isClosed()) {
            // force the thread to sleep to ensure the connection has a chance to fully flush before closing
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            connection.close();
        }
    }

}
