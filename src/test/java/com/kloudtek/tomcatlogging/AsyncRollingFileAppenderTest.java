package com.kloudtek.tomcatlogging;

import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;
import org.apache.log4j.MDC;
import org.apache.log4j.SimpleLayout;
import org.apache.log4j.rolling.FixedWindowRollingPolicy;
import org.apache.log4j.rolling.SizeBasedTriggeringPolicy;
import org.testng.annotations.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import static org.testng.Assert.*;

/**
 * Created by yannick on 10/16/15.
 */
public class AsyncRollingFileAppenderTest {
    @Test(enabled = true)
    public void testLog() throws Exception{
        final File logFile = new File("target/test.log");
        if( logFile.exists() ) {
            logFile.delete();
        }
        MDC.put("mdckey","mdcval");
//        System.setProperty("log4j.debug","true");
        Logger.getLogger(AsyncRollingFileAppenderTest.class).info("\\\"TEST",new IOException());
        Thread.sleep(1500);
        try(FileInputStream fs = new FileInputStream(logFile)) {
            final String logs = IOUtils.toString(fs);
            System.out.println(logs);
        }
        Thread.sleep(500);
    }
}