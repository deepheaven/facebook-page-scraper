package cmdline;

import common.*;
import db.DbManager;

import java.io.File;
import java.sql.Connection;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

public class FbCollector
{
    public static long sincePointer = 0;

    public static long untilPointer = System.currentTimeMillis();

    public static final int dayInMillis = 86400000;

    public static final int hourInMillis = 3600000;

    public static final int minuteInMillis = 60000;

    public static final int statsSlice = 5 * dayInMillis;

    public static final int historicSlice = dayInMillis;

    public static int scrapeCount = 0;

    public static boolean collectStats;

    public static long statsStartedAt;

    private static boolean fetch = true;

    public static void main(String[] args) throws Exception
    {
        System.out.println(Util.getDbDateTimeEst() + " started fetching data");

        for(String page: Config.pages)
        {
            PageCollector pageCollector = new PageCollector(page);
            pageCollector.collect();
        }

        if(Config.collectOnce)
        {
            collectOnce();
        }
        else
        {
            collectStatsData();
            collectHistoricData();
            while (true)
            {
                if(fetch)
                {
                    if(System.currentTimeMillis() > (statsStartedAt + 20 * minuteInMillis))
                    {
                        collectStatsData();
                    }
                    else
                    {
                        collectHistoricData();
                    }
                }
            }
        }
    }

    public static void collectOnce()
    {
        if(null == Config.until || Config.until.isEmpty() || !Config.until.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}"))
        {
            Config.until = Util.getCurDateTimeUtc();
        }

        for(String page: Config.pages)
        {
            PostsCollector postsCollector = new PostsCollector(new Page(page), Config.since, Config.until);
            postsCollector.collect();
        }
    }

    public static void collectStatsData()
    {
        collectStats = true;

        statsStartedAt = System.currentTimeMillis();

        initUntilPointer();

        long statsSince = untilPointer - statsSlice;
        long configSince = Util.toMillis(Config.since);
        statsSince = statsSince > configSince ? statsSince : configSince;

        String tempSince = Util.getDateTimeUtc(statsSince);
        String tempUntil = Util.getDateTimeUtc(untilPointer);

        if(untilPointer > (statsStartedAt - statsSlice))
        {
            System.out.println(Util.getDbDateTimeEst() + " fetching stats data from " + tempSince + " to " + tempUntil);

            int tempPostsCount = 0;
            for(String page: Config.pages)
            {
                PageCollector pageCollector = new PageCollector(page);
                pageCollector.collect();

                PostsCollector postsCollector = new PostsCollector(new Page(page), tempSince, tempUntil);
                postsCollector.collect();

                tempPostsCount += postsCollector.postIds.size();
            }

            System.out.println(Util.getDbDateTimeEst() + " fetched " + tempPostsCount + " posts");
        }

        /*if(scrapeCount == 0)
        {
            Util.sleep(Config.waitTime);
        }
        else*/
        {
            Util.sleep(600);
        }
    }

    public static void collectHistoricData()
    {
        collectStats = false;

        initUntilPointer();

        initSincePointer();

        String tempSince = Util.getDateTimeUtc(sincePointer);
        String tempUntil = Util.getDateTimeUtc(sincePointer + historicSlice);

        System.out.println(Util.getDbDateTimeEst() + " fetching historic data from " + tempSince + " to " + tempUntil);

        int tempPostsCount = 0;
        int tempCommentsCount = 0;
        int tempLikesCount = 0;
        for(String page: Config.pages)
        {
            PostsCollector postsCollector = new PostsCollector(new Page(page), tempSince, tempUntil);
            postsCollector.collect();

            tempPostsCount += postsCollector.postIds.size();
            tempCommentsCount += postsCollector.commentsCount;
            tempLikesCount += postsCollector.likesCount;
        }

        System.out.println(Util.getDbDateTimeEst() + " fetched " +
                tempPostsCount + " posts, " +
                tempCommentsCount + " comments, " +
                tempLikesCount + " likes");

        fetch = true;
        if(sincePointer == Util.toMillis(Config.since))
        {
            scrapeCount++;
            System.out.println(Util.getDbDateTimeEst() + " scraped " + scrapeCount + " time(s)");
            Config.init();
            fetch = !Config.collectOnce;
        }
    }

    private static void initUntilPointer()
    {
        if(null == Config.until || Config.until.isEmpty() || !Config.until.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}"))
        {
            untilPointer = System.currentTimeMillis();
        }
        else
        {
            untilPointer = Util.toMillis(Config.until);
        }
    }

    private static void initSincePointer()
    {
        long configSince = Util.toMillis(Config.since);

        if(sincePointer == 0 || sincePointer == configSince)
        {
            sincePointer = untilPointer - statsSlice - historicSlice;
        }
        else
        {
            sincePointer = sincePointer - historicSlice;
        }

        if(sincePointer < configSince)
        {
            sincePointer = configSince;
        }
    }
}
