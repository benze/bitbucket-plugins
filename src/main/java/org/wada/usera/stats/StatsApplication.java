package org.wada.usera.stats;

import org.apache.commons.io.FileUtils;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.util.DigestUtils;
import org.springframework.util.FileCopyUtils;
import org.springframework.util.SerializationUtils;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@SpringBootApplication
public class StatsApplication {
    static org.slf4j.Logger logger = LoggerFactory.getLogger(StatsApplication.class.getName());

    private  String URL;
    private Connection connection;
    private Set<String> hashCache = new HashSet<>();
    private boolean enableSkip = true;
    private File dataFile;

    public StatsApplication(File dataPath) {
        dataFile = new File(dataPath, this.getClass().getName() + ".dat");
        // if the data file exists, load it
        try {
            if (dataFile.exists()) {
                logger.debug( "Loading previous data set from {}", dataFile.getAbsolutePath());
                hashCache = (HashSet<String>) SerializationUtils.deserialize(FileCopyUtils.copyToByteArray(dataFile));
                logger.debug( "Successfully loaded {} entries", hashCache.size());
            }
        } catch (IOException e) {
            logger.warn("Cannot load previous data from {}.  Continuing without it.", dataFile.getName());
        }
    }

    // Known patterns to skip over stack trace when found
    private String skipPattern[] = { ".*AbstractSQLQueriesBuilder.*",
                                    ".*UserAccount\\..*Accessible(Athletes|NonAthletes|DCFs|Tests).*"
    };

    // Known patterns to force processing even if already processed in the past
    private String forcePattern[]={".*AsynchHelper\\.runBackgroundReport.*"};


    public void initH2( String filename) throws SQLException{
        // get the system temp folder
        URL = "jdbc:h2:" + filename + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=TRUE";
        logger.info("Using H2 Database in {}", URL);

        boolean h2Enabled = false;
        try{
            // ensure that any previous connection is already closed
            closeH2();
            connection = DriverManager.getConnection(URL);
            h2Enabled = true;
        } catch (SQLException ex) {
            logger.error("Error loading the H2 DB {}: {}", URL, ex);
            throw ex;
        }
    }


    public void closeH2() throws SQLException {
        if( ( connection != null ) && !connection.isClosed() )
            connection.close();
    }

    /**
     * Parse the stack trace, and only retain lines that start with izone.*
     * @param stackTrace
     * @return
     */
    private String trimStackTrace(String stackTrace){
        return Arrays.stream(stackTrace.split("\n")).filter(s -> s.matches("[\t ]+izone.*") && !s.contains("SqlLoggerAspect")).collect(Collectors.joining("\n"));
    }

    /**
     * Strip out any types of calls that are of no interest
     * @param stackTrace
     * @return
     */
    private boolean contains(String[] pattern, String stackTrace){
        Pattern p = Pattern.compile(Arrays.stream(pattern).collect(Collectors.joining("|", "(", ")")));

        return p.matcher(stackTrace).find();
    }

    public void processStackTraces() throws SQLException {
        int timesLogged = 0;
        PreparedStatement preparedStatement = connection.prepareStatement("SELECT * from USERA_TRACE");
        ResultSet rs = preparedStatement.executeQuery();
        int tracesFound = 0;
        while(rs.next()){
            tracesFound++;
            String trimmedStackTrace = trimStackTrace(rs.getString("STACKTRACE"));
            if( enableSkip && contains(skipPattern, trimmedStackTrace)){
                continue;
            }

            // calculate the hash and only display if it is new
            String md5 = DigestUtils.md5DigestAsHex(trimmedStackTrace.getBytes());
            if( contains(forcePattern, trimmedStackTrace) || !hashCache.contains(md5)) {
                hashCache.add(md5);
                logger.info("[{}][{}]\n{}", rs.getTimestamp("LAST_UPDATED"), URL, trimmedStackTrace);
            }
        }
        logger.info("{} traces analyzed [{}]", tracesFound, URL);
        preparedStatement.close();
    }


    /**
     * Call to shutdown the class.  Store the hashset to a file
     */
    public void shutdown(){
        if( dataFile != null ) {
            try {
                FileCopyUtils.copy(SerializationUtils.serialize(hashCache), dataFile);
                dataFile = null;
            } catch (IOException e) {
                logger.error("Cannot save hash data to file {}", dataFile.getName());
            }
        }
    }

    @Override
    protected void finalize() throws Throwable {
        if( dataFile != null)
            shutdown();
        super.finalize();
    }

    public static void main(String[] args) {

        // arg[0] is the folder to process
        String basePath = args.length > 0 ? args[0] : ".";
        Iterator<File> it = FileUtils.iterateFiles(new File(basePath ), new String[]{"h2.db"}, false);

        StatsApplication app = new StatsApplication(new File(basePath ));
        while( it.hasNext()){
            File db = it.next();
            try {
                logger.info("Processing database: {}", db.getName());
                app.initH2(db.getAbsolutePath().replace("\\", "/").replace(".h2.db", ""));
                app.enableSkip = true;
                app.processStackTraces();
                app.closeH2();
            } catch( SQLException e){
                logger.error( "Error Processing {}. {}", db.getName(), e);
            }
        }

        // store the list of processed traces in a local file
        app.shutdown();
    }
}
