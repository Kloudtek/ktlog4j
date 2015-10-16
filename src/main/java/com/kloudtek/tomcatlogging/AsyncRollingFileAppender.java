package com.kloudtek.tomcatlogging;

import org.apache.log4j.Appender;
import org.apache.log4j.FileAppender;
import org.apache.log4j.Logger;
import org.apache.log4j.extras.DOMConfigurator;
import org.apache.log4j.helpers.AppenderAttachableImpl;
import org.apache.log4j.helpers.LogLog;
import org.apache.log4j.helpers.QuietWriter;
import org.apache.log4j.rolling.RollingPolicy;
import org.apache.log4j.rolling.RolloverDescription;
import org.apache.log4j.rolling.TriggeringPolicy;
import org.apache.log4j.rolling.helper.Action;
import org.apache.log4j.spi.ErrorHandler;
import org.apache.log4j.spi.LoggingEvent;
import org.apache.log4j.spi.OptionHandler;
import org.apache.log4j.xml.UnrecognizedElementHandler;
import org.w3c.dom.Element;

import java.io.*;
import java.text.MessageFormat;
import java.util.*;

/**
 * Asynchronous Rolling Appender.
 * This class is based on log4j extras AsyncAppender and RollingFileAppender.
 * The reason for it's existence is that AsyncAppender doesn't support properties files,
 * and tomcat log4j logging doesn't play well with XML logging (see https://tomcat.apache.org/tomcat-8.0-doc/logging.html) *sigh*.
 */
public class AsyncRollingFileAppender extends FileAppender implements UnrecognizedElementHandler {
    private TriggeringPolicy triggeringPolicy;
    private RollingPolicy rollingPolicy;
    private long fileLength = 0L;
    private Action lastRolloverAsyncAction = null;
    public static final int DEFAULT_BUFFER_SIZE = 128;
    private final List<LoggingEvent> buffer = new ArrayList<>();
    private final Map<String, DiscardSummary> discardMap = new HashMap<>();
    private int bufferSize = 128;
    AppenderAttachableImpl aai;
    private final Thread dispatcher;
    private boolean locationInfo = false;
    private boolean blocking = true;

    public AsyncRollingFileAppender() {
        this.dispatcher = new Thread(new Dispatcher(this, this.buffer, this.discardMap));
        this.dispatcher.setDaemon(true);
        this.dispatcher.setName("AsyncAppender-Dispatcher-" + this.dispatcher.getName());
        this.dispatcher.start();
    }

    public void activateOptions() {
        if (this.rollingPolicy == null) {
            LogLog.warn("Please set a rolling policy for the RollingFileAppender named \'" + this.getName() + "\'");
        } else {
            if (this.triggeringPolicy == null && this.rollingPolicy instanceof TriggeringPolicy) {
                this.triggeringPolicy = (TriggeringPolicy) this.rollingPolicy;
            }

            if (this.triggeringPolicy == null) {
                LogLog.warn("Please set a TriggeringPolicy for the RollingFileAppender named \'" + this.getName() + "\'");
            } else {
                Exception exception = null;
                synchronized (this) {
                    this.triggeringPolicy.activateOptions();
                    this.rollingPolicy.activateOptions();

                    try {
                        RolloverDescription ex = this.rollingPolicy.initialize(this.getFile(), this.getAppend());
                        if (ex != null) {
                            Action activeFile = ex.getSynchronous();
                            if (activeFile != null) {
                                activeFile.execute();
                            }

                            this.setFile(ex.getActiveFileName());
                            this.setAppend(ex.getAppend());
                            this.lastRolloverAsyncAction = ex.getAsynchronous();
                            if (this.lastRolloverAsyncAction != null) {
                                Thread runner = new Thread(this.lastRolloverAsyncAction);
                                runner.start();
                            }
                        }

                        File activeFile1 = new File(this.getFile());
                        if (this.getAppend()) {
                            this.fileLength = activeFile1.length();
                        } else {
                            this.fileLength = 0L;
                        }

                        super.activateOptions();
                    } catch (Exception var7) {
                        exception = var7;
                    }
                }

                if (exception != null) {
                    LogLog.warn("Exception while initializing RollingFileAppender named \'" + this.getName() + "\'", exception);
                }

            }
        }
    }

    private QuietWriter createQuietWriter(Writer writer) {
        Object handler = this.errorHandler;
        if (handler == null) {
            handler = new DefaultErrorHandler(this);
        }

        return new QuietWriter(writer, (ErrorHandler) handler);
    }

    @Override
    public void append(LoggingEvent event) {
        if (this.dispatcher != null && this.dispatcher.isAlive() && this.bufferSize > 0) {
            event.getNDC();
            event.getThreadName();
            event.getMDCCopy();
            if (this.locationInfo) {
                event.getLocationInformation();
            }
            event.getRenderedMessage();
            event.getThrowableStrRep();
            synchronized (this.buffer) {
                while (true) {
                    int previousSize = this.buffer.size();
                    if (previousSize < this.bufferSize) {
                        this.buffer.add(event);
                        if (previousSize == 0) {
                            this.buffer.notifyAll();
                        }
                        break;
                    }

                    boolean discard = true;
                    if (this.blocking && !Thread.interrupted() && Thread.currentThread() != this.dispatcher) {
                        try {
                            this.buffer.wait();
                            discard = false;
                        } catch (InterruptedException var8) {
                            Thread.currentThread().interrupt();
                        }
                    }

                    if (discard) {
                        String loggerName = event.getLoggerName();
                        DiscardSummary summary = (DiscardSummary) this.discardMap.get(loggerName);
                        if (summary == null) {
                            summary = new DiscardSummary(event);
                            this.discardMap.put(loggerName, summary);
                        } else {
                            summary.add(event);
                        }
                        break;
                    }
                }

            }
        } else {
            doAppend(event);
        }
    }

    public void doAppend(LoggingEvent event) {
        super.append(event);
    }


    public void close() {
        synchronized (this) {
            if (this.lastRolloverAsyncAction != null) {
                this.lastRolloverAsyncAction.close();
            }
        }
        List e = this.buffer;
        synchronized (this.buffer) {
            this.closed = true;
            this.buffer.notifyAll();
        }

        try {
            this.dispatcher.join();
        } catch (InterruptedException var5) {
            Thread.currentThread().interrupt();
            LogLog.error("Got an InterruptedException while waiting for the dispatcher to finish.", var5);
        }

    }

    public boolean getLocationInfo() {
        return this.locationInfo;
    }

    public void setLocationInfo(boolean flag) {
        this.locationInfo = flag;
    }

    public void setBufferSize(int size) {
        if (size < 0) {
            throw new NegativeArraySizeException("size");
        } else {
            List var2 = this.buffer;
            synchronized (this.buffer) {
                this.bufferSize = size < 1 ? 1 : size;
                this.buffer.notifyAll();
            }
        }
    }

    public int getBufferSize() {
        return this.bufferSize;
    }

    public void setBlocking(boolean value) {
        List var2 = this.buffer;
        synchronized (this.buffer) {
            this.blocking = value;
            this.buffer.notifyAll();
        }
    }

    public boolean getBlocking() {
        return this.blocking;
    }


    public boolean rollover() {
        if (this.rollingPolicy != null) {
            Exception exception = null;
            synchronized (this) {
                if (this.lastRolloverAsyncAction != null) {
                    this.lastRolloverAsyncAction.close();
                }

                try {
                    RolloverDescription ex = this.rollingPolicy.rollover(this.getFile());
                    if (ex != null) {
                        if (ex.getActiveFileName().equals(this.getFile())) {
                            this.closeWriter();
                            boolean newWriter = true;
                            if (ex.getSynchronous() != null) {
                                newWriter = false;

                                try {
                                    newWriter = ex.getSynchronous().execute();
                                } catch (Exception var9) {
                                    exception = var9;
                                }
                            }

                            if (newWriter) {
                                if (ex.getAppend()) {
                                    this.fileLength = (new File(ex.getActiveFileName())).length();
                                } else {
                                    this.fileLength = 0L;
                                }

                                if (ex.getAsynchronous() != null) {
                                    this.lastRolloverAsyncAction = ex.getAsynchronous();
                                    (new Thread(this.lastRolloverAsyncAction)).start();
                                }

                                this.setFile(ex.getActiveFileName(), ex.getAppend(), this.bufferedIO, this.bufferSize);
                            } else {
                                this.setFile(ex.getActiveFileName(), true, this.bufferedIO, this.bufferSize);
                                if (exception == null) {
                                    LogLog.warn("Failure in post-close rollover action");
                                } else {
                                    LogLog.warn("Exception in post-close rollover action", exception);
                                }
                            }
                        } else {
                            OutputStreamWriter newWriter1 = this.createWriter(this.createFileOutputStream(ex.getActiveFileName(), ex.getAppend()));
                            this.closeWriter();
                            this.setFile(ex.getActiveFileName());
                            this.qw = this.createQuietWriter(newWriter1);
                            boolean success = true;
                            if (ex.getSynchronous() != null) {
                                success = false;

                                try {
                                    success = ex.getSynchronous().execute();
                                } catch (Exception var8) {
                                    ;
                                }
                            }

                            if (success) {
                                if (ex.getAppend()) {
                                    this.fileLength = (new File(ex.getActiveFileName())).length();
                                } else {
                                    this.fileLength = 0L;
                                }

                                if (ex.getAsynchronous() != null) {
                                    this.lastRolloverAsyncAction = ex.getAsynchronous();
                                    (new Thread(this.lastRolloverAsyncAction)).start();
                                }
                            }

                            this.writeHeader();
                        }

                        boolean var10000 = true;
                        return var10000;
                    }
                } catch (Exception var10) {
                    exception = var10;
                }
            }

            if (exception != null) {
                LogLog.warn("Exception during rollover, rollover deferred.", exception);
            }
        }

        return false;
    }

    private FileOutputStream createFileOutputStream(String newFileName, boolean append) throws FileNotFoundException {
        try {
            return new FileOutputStream(newFileName, append);
        } catch (FileNotFoundException var6) {
            String parentName = (new File(newFileName)).getParent();
            if (parentName != null) {
                File parentDir = new File(parentName);
                if (!parentDir.exists() && parentDir.mkdirs()) {
                    return new FileOutputStream(newFileName, append);
                } else {
                    throw var6;
                }
            } else {
                throw var6;
            }
        }
    }

    protected void subAppend(LoggingEvent event) {
        if (this.triggeringPolicy.isTriggeringEvent(this, event, this.getFile(), this.getFileLength())) {
            try {
                this.rollover();
            } catch (Exception var3) {
                LogLog.warn("Exception during rollover attempt.", var3);
            }
        }

        super.subAppend(event);
    }

    public RollingPolicy getRollingPolicy() {
        return this.rollingPolicy;
    }

    public TriggeringPolicy getTriggeringPolicy() {
        return this.triggeringPolicy;
    }

    public void setRollingPolicy(RollingPolicy policy) {
        this.rollingPolicy = policy;
    }

    public void setTriggeringPolicy(TriggeringPolicy policy) {
        this.triggeringPolicy = policy;
    }

    protected OutputStreamWriter createWriter(OutputStream os) {
        return super.createWriter(new CountingOutputStream(os, this));
    }

    public long getFileLength() {
        return this.fileLength;
    }

    public synchronized void incrementFileLength(int increment) {
        this.fileLength += (long) increment;
    }

    public boolean parseUnrecognizedElement(Element element, Properties props) throws Exception {
        String nodeName = element.getNodeName();
        OptionHandler triggerPolicy;
        if ("rollingPolicy".equals(nodeName)) {
            triggerPolicy = DOMConfigurator.parseElement(element, props, RollingPolicy.class);
            if (triggerPolicy != null) {
                triggerPolicy.activateOptions();
                this.setRollingPolicy((RollingPolicy) triggerPolicy);
            }

            return true;
        } else if ("triggeringPolicy".equals(nodeName)) {
            triggerPolicy = DOMConfigurator.parseElement(element, props, TriggeringPolicy.class);
            if (triggerPolicy != null) {
                triggerPolicy.activateOptions();
                this.setTriggeringPolicy((TriggeringPolicy) triggerPolicy);
            }

            return true;
        } else {
            return false;
        }
    }

    private static final class DefaultErrorHandler implements ErrorHandler {
        private final AsyncRollingFileAppender appender;

        public DefaultErrorHandler(AsyncRollingFileAppender appender) {
            this.appender = appender;
        }

        public void setLogger(Logger logger) {
        }

        public void error(String message, Exception ioe, int errorCode) {
            this.appender.close();
            LogLog.error("IO failure for appender named " + this.appender.getName(), ioe);
        }

        public void error(String message) {
        }

        public void error(String message, Exception e, int errorCode, LoggingEvent event) {
        }

        public void setAppender(Appender appender) {
        }

        public void setBackupAppender(Appender appender) {
        }

        public void activateOptions() {
        }
    }

    private static class CountingOutputStream extends OutputStream {
        private final OutputStream os;
        private final AsyncRollingFileAppender rfa;

        public CountingOutputStream(OutputStream os, AsyncRollingFileAppender rfa) {
            this.os = os;
            this.rfa = rfa;
        }

        public void close() throws IOException {
            this.os.close();
        }

        public void flush() throws IOException {
            this.os.flush();
        }

        public void write(byte[] b) throws IOException {
            this.os.write(b);
            this.rfa.incrementFileLength(b.length);
        }

        public void write(byte[] b, int off, int len) throws IOException {
            this.os.write(b, off, len);
            this.rfa.incrementFileLength(len);
        }

        public void write(int b) throws IOException {
            this.os.write(b);
            this.rfa.incrementFileLength(1);
        }
    }

    private static class Dispatcher implements Runnable {
        private final AsyncRollingFileAppender parent;
        private final List buffer;
        private final Map discardMap;

        public Dispatcher(AsyncRollingFileAppender parent, List buffer, Map discardMap) {
            this.parent = parent;
            this.buffer = buffer;
            this.discardMap = discardMap;
        }

        public void run() {
            boolean isActive = true;

            try {
                while (isActive) {
                    LoggingEvent[] ex = null;
                    List i = this.buffer;
                    synchronized (this.buffer) {
                        int bufferSize = this.buffer.size();

                        for (isActive = !this.parent.closed; bufferSize == 0 && isActive; isActive = !this.parent.closed) {
                            this.buffer.wait();
                            bufferSize = this.buffer.size();
                        }

                        if (bufferSize > 0) {
                            ex = new LoggingEvent[bufferSize + this.discardMap.size()];
                            this.buffer.toArray(ex);
                            int index = bufferSize;

                            for (Iterator iter = this.discardMap.values().iterator(); iter.hasNext(); ex[index++] = ((DiscardSummary) iter.next()).createEvent()) {
                                //
                            }

                            this.buffer.clear();
                            this.discardMap.clear();
                            this.buffer.notifyAll();
                        }
                    }

                    if (ex != null) {
                        for (LoggingEvent event : ex) {
                            synchronized (parent) {
                                parent.doAppend(event);
                            }
                        }
                    }
                }
            } catch (InterruptedException var11) {
                Thread.currentThread().interrupt();
            }

        }
    }

    private static final class DiscardSummary {
        private LoggingEvent maxEvent;
        private int count;

        public DiscardSummary(LoggingEvent event) {
            this.maxEvent = event;
            this.count = 1;
        }

        public void add(LoggingEvent event) {
            if (event.getLevel().toInt() > this.maxEvent.getLevel().toInt()) {
                this.maxEvent = event;
            }
            ++this.count;
        }

        public LoggingEvent createEvent() {
            String msg = MessageFormat.format("Discarded {0} messages due to full event buffer including: {1}", new Object[]{new Integer(this.count), this.maxEvent.getMessage()});
            return new LoggingEvent("org.apache.log4j.AsyncAppender.DONT_REPORT_LOCATION", Logger.getLogger(this.maxEvent.getLoggerName()), this.maxEvent.getLevel(), msg, (Throwable) null);
        }
    }
}
