package common.comment;

import common.page.Page;
import common.post.Post;
import common.Util;
import db.DbManager;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CommentsInserter
{
    private static Pattern pattern = Pattern.compile(
            "(\\d{4}-\\d{2}-\\d{2})_(\\d{2}-\\d{2}-\\d{2})_(\\d+)_(\\d+)_(post_comments|comment_replies).json");
    private File commentsJsonFile;
    private String postId;
    private String crawlDate;
    private String crawlTime;
    private String dbCrawlDateTime;
    private String pageId;
    private String username;
    private String commentId;

    public CommentsInserter(File commentsJsonFile)
    {
        this.commentsJsonFile = commentsJsonFile;
        Matcher matcher = pattern.matcher(commentsJsonFile.getName());
        if(matcher.matches())
        {
            crawlDate = matcher.group(1);
            crawlTime = matcher.group(2);
            dbCrawlDateTime = crawlDate + " " + crawlTime.replaceAll("-", ":");
            if(matcher.group(5).equals("post_comments"))
            {
                pageId = matcher.group(3);
                postId = matcher.group(3) + "_" + matcher.group(4);
            }
            else
            {
                commentId = matcher.group(3) + "_" + matcher.group(4);
                postId = DbManager.getFieldValue("Comment", "post_id", "id", commentId);
                pageId = DbManager.getFieldValue("Post", "page_id", "id", postId);
            }
            username = Page.getUsername(pageId);
        }
    }

    public CommentsInserter(String postId)
    {
        this.postId = postId;
    }

    public void processComments()
    {
        JSONObject commentsJson = null;
        InputStream is = null;
        try
        {
            is = new FileInputStream(commentsJsonFile);
            JSONParser parser = new JSONParser();
            commentsJson = (JSONObject) parser.parse(new InputStreamReader(is, Charset.forName("UTF-8")));
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        finally
        {
            try { if(null != is) is.close(); } catch (Exception e) { e.printStackTrace(); }
        }

        List<Comment> allComments = new ArrayList<Comment>();
        JSONArray commentsData = (JSONArray) commentsJson.get("data");
        Iterator itr = commentsData.iterator();
        while (itr.hasNext())
        {
            JSONObject commentJson = (JSONObject) itr.next();
            Comment comment = new Comment(commentJson, postId, commentId);
            allComments.add(comment);
        }

        updateDb(allComments);

        Util.sleepMillis(100);

        boolean success;

        if(null == commentId)
        {
            success = allComments.size() <= DbManager.getInt("SELECT COUNT(*) AS count FROM Comment WHERE post_id='" + postId + "'");
        }
        else
        {
            success = allComments.size() <= DbManager.getInt("SELECT COUNT(*) AS count FROM Comment WHERE parent_id='" + commentId + "'");
        }

        if(success)
        {
            String dir = Util.buildPath("archive", username, "posts", postId);
            String path = dir + "/" + commentsJsonFile.getName();
            success = commentsJsonFile.renameTo(new File(path));
            if(!success)
            {
                System.err.println(Util.getDbDateTimeEst() + " failed to move " + commentsJsonFile.getAbsolutePath() + " to " + path);
                System.exit(0);
            }
        }
    }

    public void updateDb(List<Comment> comments)
    {
        List<Comment> insertComments = new ArrayList<Comment>();
        List<Comment> updateComments = new ArrayList<Comment>();
        Connection connection = DbManager.getConnection();
        String query = "SELECT id FROM Comment WHERE id=?";
        PreparedStatement statement = null;
        ResultSet resultSet = null;
        try
        {
            statement = connection.prepareStatement(query);
            for(Comment comment: comments)
            {
                statement.setString(1, comment.getId());
                resultSet = statement.executeQuery();
                if(resultSet.next())
                {
                    updateComments.add(comment);
                }
                else
                {
                    insertComments.add(comment);
                }
            }
        }
        catch (SQLException e)
        {
            e.printStackTrace();
        }
        finally
        {
            if(null != resultSet) try { resultSet.close(); } catch (SQLException e) { e.printStackTrace(); }
            if(null != statement) try { statement.close(); } catch (SQLException e) { e.printStackTrace(); }
            if(null != connection) try { connection.close(); } catch (SQLException e) { e.printStackTrace(); }
        }
        insertComments(insertComments);
        updateComments(updateComments);
    }

    public void insertComments(List<Comment> comments)
    {
        final int batchSize = 100;
        int count = 0;
        Connection connection = DbManager.getConnection();
        String query = "INSERT INTO Comment "
                + "(id, post_id, message, created_at, from_id, from_name, likes, replies, parent_id) "
                + "VALUES (?,?,?,?,?,?,?,?,?)";
        PreparedStatement statement = null;
        try
        {
            statement = connection.prepareStatement(query);
            for(Comment comment: comments)
            {
                statement.setString(1, comment.getId());
                statement.setString(2, postId);
                statement.setString(3, comment.getMessage());
                statement.setString(4, Util.toDbDateTime(comment.getCreatedAt()));
                statement.setString(5, comment.getFromId());
                statement.setString(6, comment.getFromName());
                statement.setInt(7, comment.getLikes());
                statement.setInt(8, comment.getReplies());
                statement.setString(9, commentId);
                statement.addBatch();

                if(++count % batchSize == 0)
                {
                    statement.executeBatch();
                }
            }
            statement.executeBatch();
        }
        catch (SQLException e)
        {
            e.printStackTrace();
        }
        finally
        {
            if(null != statement) try { statement.close(); } catch (SQLException e) { e.printStackTrace(); }
            if(null != connection) try { connection.close(); } catch (SQLException e) { e.printStackTrace(); }
        }
    }

    public void updateComments(List<Comment> comments)
    {
        final int batchSize = 100;
        int count = 0;
        Connection connection = DbManager.getConnection();
        String query = "UPDATE Comment SET message=?,likes=?,replies=?,parent_id=? WHERE id=?";
        PreparedStatement statement = null;
        try
        {
            statement = connection.prepareStatement(query);
            for(Comment comment: comments)
            {
                statement.setString(1, comment.getMessage());
                statement.setInt(2, comment.getLikes());
                statement.setInt(3, comment.getReplies());
                statement.setString(4, commentId);
                statement.setString(5, comment.getId());
                statement.addBatch();

                if(++count % batchSize == 0)
                {
                    statement.executeBatch();
                }
            }
            statement.executeBatch();
        }
        catch (SQLException e)
        {
            e.printStackTrace();
        }
        finally
        {
            if(null != statement) try { statement.close(); } catch (SQLException e) { e.printStackTrace(); }
            if(null != connection) try { connection.close(); } catch (SQLException e) { e.printStackTrace(); }
        }
    }
}