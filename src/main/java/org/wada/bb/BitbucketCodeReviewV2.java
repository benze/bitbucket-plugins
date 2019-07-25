package org.wada.bb;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.ReadContext;
import org.slf4j.Logger;
import org.springframework.http.client.support.BasicAuthenticationInterceptor;
import org.springframework.web.client.RestTemplate;

import java.sql.SQLException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.slf4j.LoggerFactory.getLogger;

public class BitbucketCodeReviewV2 {
    // get a static slf4j logger for the class
    protected static final Logger logger = getLogger(BitbucketCodeReviewV2.class);
    private final boolean dryRun;

    private final String BITBUCKET_USER = "erbe";
    private final String BITBUCKET_AUTH = "MjcxMTI2Mjg2MDU2OnIsggP395a2gIpy7S6wjFbID2Lc";
    private final String BITBUCKET_ACTIVITIES_ENDPOINT = "https://bitbucket.wada-ama.org/rest/api/1.0/projects/{0}/repos/{1}/pull-requests/{2}/activities?limit=500";

    private final String JIRA_ISSUE_ENDPOINT = "https://wada-ama.atlassian.net/rest/api/2/issue";
    private final String JIRA_USER = "eric.benzacar@wada-ama.org";
    private final String JIRA_AUTH = "5TbjcDD8mSsKANMvC8wWF268";

    private final PersistentCache cache;


    public BitbucketCodeReviewV2(boolean dryRun) throws SQLException {
        cache = new PersistentCache();
        this.dryRun = dryRun;
    }

    /**
     * Get all activities from a PR (up to the max 500) and create {@link Comment} objects for each
     *
     * @param pullRequestId
     * @return
     */
    public List<Comment> getPullRequestComments(String projectKey, String repository, int pullRequestId) {
        final RestTemplate bitbucketRestTemplate = new RestTemplate();
        bitbucketRestTemplate.getInterceptors().add(new BasicAuthenticationInterceptor(BITBUCKET_USER, BITBUCKET_AUTH));

        // get list of all activities in the PR
        String response = bitbucketRestTemplate.getForObject(MessageFormat.format(BITBUCKET_ACTIVITIES_ENDPOINT, projectKey, repository, pullRequestId), String.class);

        // $.values[?(@.comment)]['id','comment','commentAnchor']
        ReadContext ctx = JsonPath.parse(response);
        List<Map<String, Object>> comments = ctx.read("$.values[?(@.comment)]['id','comment','commentAnchor']");
        logger.debug("Found {} comments in pull request {}", comments.size(), pullRequestId);

        List<Comment> parsedComments = comments.stream().map(comment -> {
            int commentId = (int) comment.get("id");
            Map<String, Object> content = (Map) comment.get("comment");
            Map<String, Object> anchor = (Map) comment.get("commentAnchor");

            if( anchor == null){
                return new Comment(projectKey, repository, pullRequestId, commentId,
                        null, (String)content.get("text"), null, null);
            } else {
                return new Comment(projectKey, repository, pullRequestId, commentId,
                        (String) anchor.get("path"), (String) content.get("text"),
                        (String) anchor.get("toHash"), (String) anchor.get("fromHash"));
            }
        }).collect(Collectors.toList());



        return parsedComments;
    }


    /**
     * Create a jira ticket for each ticket in the list
     *
     * @param jiraTicket
     * @return
     */
    public String createJiraTicket(String jiraProject, String epicKey, JiraTicket jiraTicket ) {

        final RestTemplate jiraRestTemplate = new RestTemplate();
        jiraRestTemplate.getInterceptors().add(new BasicAuthenticationInterceptor( JIRA_USER, JIRA_AUTH));

        // create the jiraIssue
        JiraTicket.IssueUpdate issueUpdate = jiraTicket.convertToJiraIssue(jiraProject, epicKey);
        String issueKey = null;
        if( dryRun ){
            logger.info( "DRY_RUN_ONLY: Should be creating: {}", issueUpdate.toString());
        } else {
            // actually create the JIRA tickets and update the cache
            try {
                String response  = jiraRestTemplate.postForObject(JIRA_ISSUE_ENDPOINT, issueUpdate, String.class);
                issueKey = JsonPath.read(response, "$.key");
            } catch (Exception e) {
                ObjectMapper mapper = new ObjectMapper();
                try {
                    logger.error("Couldn't create issue\n{}", mapper.writeValueAsString(issueUpdate));
                } catch (JsonProcessingException ex) {
                    ex.printStackTrace();
                }
            }


            // add the issue to the cache
            if( issueKey != null ) {
                logger.info("Created CR Task: {}", issueKey);
                final String jiraKey = issueKey;
                jiraTicket.getComments().stream().forEach(comment -> {
                    try {
                        logger.debug( "Adding commentId[{}], jiraId[{}] to the cache", comment.getId(), jiraKey);
                        cache.put(comment.getId(), jiraKey);
                    } catch (SQLException e) {
                        e.printStackTrace();
                    }
                });
            }
        }
        // return the key created
        return issueKey;
    }


    /**
     * Retrieve only the new top-level comments that have not already had Jira tickets created for them
     * @return a List<Comment> mapped by filePath
     */
    private Map<String, List<Comment>> getNewComments(String projectKey, String repository, int pullRequestId){
        return getPullRequestComments(projectKey, repository, pullRequestId).stream()
                // skip any issues that have already been created
                .filter(comment -> cache.get(comment.getId()) == null)
                // map by file
                .collect(Collectors.toMap(Comment::getFilePath,
                        comment -> {
                            // return a list with the comment
                            List<Comment> comments = new ArrayList<>();
                            comments.add(comment);
                            return comments;
                        },
                        (comments, newComment) -> {
                            // if an existing List<comments> is already found, add the new List<> to it and return it
                            comments.addAll(newComment);
                            return comments;
                        }));
    }


    /**
     * Create a list of JiraTickets to create
     * @param commentsPerFile
     * @return
     */
    private List<JiraTicket> getJiraTicketsToCreate(List<Comment> commentsPerFile){
        // need a separate jira ticket per commit hash fragment
        Map<String, List<Comment>> commentsMappedByCommitId = commentsPerFile.stream().collect(Collectors.toMap(Comment::getToHash,
                comment -> {
                    // return a list with the comment
                    List<Comment> comments = new ArrayList<>();
                    comments.add(comment);
                    return comments;
                },
                (comments, newComment) -> {
                    // if an existing List<comments> is already found, add the new List<> to it and return it
                    comments.addAll(newComment);
                    return comments;
                }));


        // loop over each commitId hash and create a Jira ticket request for all comments associated to that file
        List<JiraTicket> ticketsPerFile = commentsMappedByCommitId.values().stream().map(comments -> {
            // loop over each comment in the list
            JiraTicket jiraTicket = null;
            for (Comment comment : comments) {
                if (jiraTicket == null) {
                    jiraTicket = new JiraTicket(comment);
                } else {
                    jiraTicket.addComment(comment);
                }
            }
            return jiraTicket;
        }).collect(Collectors.toList());


        // return the list of tickets per file
        return ticketsPerFile;
    }



    public void createJiraTasks(String projectKey, String repositoryName, int pullRequestId, String jiraProject, String epicKey) throws SQLException {
        cache.open(repositoryName, pullRequestId);
        // get new comments and create Jira tickets requests for each
        List<JiraTicket> jiraTickets = new ArrayList<>();
        getNewComments(projectKey, repositoryName, pullRequestId).values().stream().map(this::getJiraTicketsToCreate).forEach(jiraTickets::addAll);

        // for each ticket, need to create the actual jira entry
        long ticketsCreated = jiraTickets.stream().map( ticket -> this.createJiraTicket(jiraProject, epicKey, ticket)).count();
//        jiraTickets.stream().findFirst().ifPresent( ticket -> this.createJiraTicket(jiraProject, epicKey, ticket));
        logger.info("Created {} new Jira tickets", ticketsCreated);

        // close the cache
        cache.close();
    }


    /**
     * Ensure the cache connection is closed
     * @throws Throwable
     */
    @Override
    protected void finalize() throws Throwable {
        cache.close();
        super.finalize();
    }

    public static void main(String[] args) throws SQLException {
        boolean dryRun = true;
        String projectKey = "ADAMS";
        String repositoryName = "adams";
        int pullRequestId = 674;
        String jiraProject = "ADAPI";
        String jiraEpic = "ADAPI-2091";

        BitbucketCodeReviewV2 bb = new BitbucketCodeReviewV2(false);
        bb.createJiraTasks(projectKey, repositoryName, pullRequestId, jiraProject, jiraEpic);

        // need to sleep
    }

    /**
     * Create a jira ticket for each ticket in the list
     *
     * @param jiraTicket
     * @return
     */
    public String updateJiraTicket(String jiraProject, String epicKey, JiraTicket jiraTicket ) {

        final RestTemplate jiraRestTemplate = new RestTemplate();
        jiraRestTemplate.getInterceptors().add(new BasicAuthenticationInterceptor( JIRA_USER, JIRA_AUTH));

        // create the jiraIssue
        JiraTicket.IssueUpdate issueUpdate = jiraTicket.convertToJiraIssue(jiraProject, epicKey);
        String issueKey = null;
        try {
            String response  = jiraRestTemplate.postForObject(JIRA_ISSUE_ENDPOINT, issueUpdate, String.class);
//            String response = "{'key':'ADAMS-123'}";
            issueKey = JsonPath.read(response, "$.key");
        } catch (Exception e) {
            ObjectMapper mapper = new ObjectMapper();
            try {
                logger.error("Couldn't create issue\n{}", mapper.writeValueAsString(issueUpdate));
            } catch (JsonProcessingException ex) {
                ex.printStackTrace();
            }
        }


        // add the issue to the cache
        if( issueKey != null ) {
            logger.info("Created CR Task: {}", issueKey);
            final String jiraKey = issueKey;
            jiraTicket.getComments().stream().forEach(comment -> {
                try {
                    logger.debug( "Adding commentId[{}], jiraId[{}] to the cache", comment.getId(), jiraKey);
                    cache.put(comment.getId(), jiraKey);
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            });
        }

        // return the key created
        return issueKey;
    }




}
