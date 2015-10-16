package com.kloudtek.log4j;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.log4j.Layout;
import org.apache.log4j.MDC;
import org.apache.log4j.spi.LocationInfo;
import org.apache.log4j.spi.LoggingEvent;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Created by yannick on 10/16/15.
 */
public class JsonLayout extends Layout {
    private final Gson gson = new GsonBuilder().create();
    private DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
    private boolean locationInfo;

    public JsonLayout() {
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    private static String toString(Object obj) {
        try {
            return obj.toString();
        } catch (Throwable e) {
            return "Error getting message: " + e.getMessage();
        }
    }

    @SuppressWarnings("ThrowableResultOfMethodCallIgnored")
    @Override
    public synchronized String format(LoggingEvent le) {
        return gson.toJson(toHashMap(le)) + "\n";
    }

    private Map<String, Object> toHashMap(LoggingEvent le) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("timestamp", dateFormat.format(new Date(le.timeStamp)));
        r.put("level", le.getLevel().toString());
        r.put("thread", le.getThreadName());
        r.put("ndc", le.getNDC());
        if( locationInfo ) {
            final LocationInfo locationInformation = le.getLocationInformation();
            r.put("classname", locationInformation.getClassName());
            r.put("filename", locationInformation.getFileName());
            r.put("linenumber", Integer.parseInt(locationInformation.getLineNumber()));
            r.put("methodname", locationInformation.getMethodName());
        }
        if (le.getMessage() != null) {
            r.put("message", toString(le.getMessage()));
        }
        if (le.getThrowableInformation() != null && le.getThrowableInformation().getThrowable() != null) {
            r.put("throwable", getThrowable(le));
        }
        r.put("mdc", MDC.getContext());
        return r;
    }

    private String getThrowable(LoggingEvent le) {
        Object[] parts = le.getThrowableStrRep();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; ; i++) {
            sb.append(parts[i]);
            if (i == parts.length - 1)
                return sb.toString();
            sb.append("\n");
        }
    }

    @Override
    public boolean ignoresThrowable() {
        return false;
    }

    @Override
    public void activateOptions() {

    }

    public boolean isLocationInfo() {
        return locationInfo;
    }

    public void setLocationInfo(boolean locationInfo) {
        this.locationInfo = locationInfo;
    }
}
