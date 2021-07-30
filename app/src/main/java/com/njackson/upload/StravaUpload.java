package com.njackson.upload;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.njackson.analytics.IAnalytics;
import com.njackson.application.PebbleBikeApplication;
import com.njackson.pebble.IMessageManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

import javax.inject.Inject;

import fr.jayps.android.AdvancedLocation;

public class StravaUpload {

    private static final String TAG = "PB-StravaUpload";

    @Inject IMessageManager _messageManager;
    @Inject SharedPreferences _sharedPreferences;
    @Inject IAnalytics _parseAnalytics;
    Activity _activity = null;
    Context _context;

    private class ApiResult {
        String message;
        int serverResponseCode;
        String serverResponseMessage;
        String status;
        String error;
        int duplicate_activity_id = 0;
    }
    public StravaUpload(Activity activity) {
        ((PebbleBikeApplication) activity.getApplicationContext()).inject(this);
        _activity = activity;
        _context = activity.getApplicationContext();
    }

    public StravaUpload(Context context) {
        ((PebbleBikeApplication) context).inject(this);
        _context = context;
    }

    public void upload(String token, final String uploadType) {
        Toast.makeText(_context, "Strava: uploading... Please wait", Toast.LENGTH_LONG).show();
        final String strava_token = token;

        new Thread(new Runnable() {
            public void run() {
               Log.i(TAG, "token: " + strava_token);

                Looper.prepare();
                AdvancedLocation advancedLocation = new AdvancedLocation(_context);
                String gpx;
                if (uploadType.equals("tcx")) {
                    gpx = advancedLocation.getTCX("Biking");
                } else {
                    gpx = advancedLocation.getGPX(false);
                }

                String message;

                ApiResult res = _upload(strava_token, gpx, uploadType);
                message = res.message;
                Log.d(TAG, "_upload: " + res.serverResponseCode + "[" + res.serverResponseMessage + "] - " + res.message);

                if (res.duplicate_activity_id > 0) {
                    Log.d(TAG, "duplicate_activity_id:" + res.duplicate_activity_id);
                    if (_sharedPreferences.getBoolean("STRAVA_DELETE_IF_EXISTS_DURING_UPLOAD", false)) {
                        ApiResult res2 = _delete(strava_token, res.duplicate_activity_id);
                        Log.d(TAG, "_delete: " + res2.serverResponseCode + "[" + res2.serverResponseMessage + "]");
                        if (res2.serverResponseCode == 204) {
                            // activity successfully deleted
                            ApiResult res3 = _upload(strava_token, gpx, uploadType);
                            Log.d(TAG, "_upload: " + res3.serverResponseCode + "[" + res3.serverResponseMessage + "] - " + res3.message);
                            message = "Your activity has been updated";
                        } else {
                            message = "An error has occurred while deleteting the previous activity";
                        }
                    }
                }


                if (_activity != null) {
                    final String _message = message;
                    _activity.runOnUiThread(new Runnable() {
                        public void run() {
                            Toast.makeText(_activity.getApplicationContext(), "Strava: " + _message, Toast.LENGTH_LONG).show();
                            Log.d(TAG, "_message:" + _message);
                        }
                    });
                }
                if (_sharedPreferences.getBoolean("STRAVA_NOTIFICATION", false)) {
                    // use _messageManager and not _bus to be able to send data even if GPS is not started
                    _messageManager.sendMessageToPebble("JayPS - Strava", message);
                }
            }
        }).start();
    }

    private ApiResult _upload(String strava_token, String gpx, final String uploadType) {
        ApiResult result = new ApiResult();

        //String tmp_url = "http://labs.jayps.fr/pebble/strava.php";
        String tmp_url = "https://www.strava.com/api/v3/uploads";
        //Log.d(TAG, "UPLOAD url="+tmp_url);

        // creates a unique boundary based on time stamp
        String boundary = "===" + System.currentTimeMillis() + "===";

        String lineEnd = "\r\n";
        String twoHyphens = "--";
        String data_type = uploadType;
        String external_id = "file" + (System.currentTimeMillis() / 1000);
        String description = "GPS track generated by JayPS, http://www.pebblebike.com";
        byte[] gpx2 = gpx.getBytes();

        boolean gzipped = true;
        if (gzipped) {
            try {
                gpx2 = compressString(gpx);
                data_type = uploadType + ".gz";
            } catch (Exception e) {
                Log.e(TAG, "Exception:" + e);
            }
        }

        try {
            URL url = new URL(tmp_url);
            HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();

            urlConnection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            String auth = "Bearer " + strava_token;
            //Log.d(TAG, "auth="+auth);
            urlConnection.setRequestProperty("Authorization", auth);
            urlConnection.setDoOutput(true);

            String data = new String();

            data += twoHyphens + boundary + lineEnd;
            // data_type
            data += "Content-Disposition: form-data; name=\"data_type\"" + lineEnd;
            data += "Content-Type: text/plain;charset=UTF-8" + lineEnd + "Content-Length: " + data_type.length() + lineEnd;
            data += lineEnd;
            data += data_type + lineEnd + twoHyphens + boundary + lineEnd;

            // external_id
                    /*data += "Content-Disposition: form-data; name=\"external_id\"" + lineEnd;
                    data += "Content-Type: text/plain;charset=UTF-8" + lineEnd + "Content-Length: " + external_id.length() + lineEnd + lineEnd;
                    data += external_id + lineEnd + twoHyphens + boundary + lineEnd;*/

            // description
            data += "Content-Disposition: form-data; name=\"description\"" + lineEnd;
            data += "Content-Type: text/plain;charset=UTF-8" + lineEnd + "Content-Length: " + description.length() + lineEnd + lineEnd;
            data += description + lineEnd + twoHyphens + boundary + lineEnd;

            // gpx
            data += "Content-Disposition: form-data; name=\"file\"; filename=\"" + external_id + "\"" + lineEnd;
            data += "Content-Length: " + gpx2.length + lineEnd;
            data += lineEnd;
            //data += gpx;
            String data2 = lineEnd + twoHyphens + boundary + twoHyphens + lineEnd;
            // end

            //Log.d(TAG, "data[" + data.length() + "]:" + data);
            //Log.d(TAG, "data2[" + data2.length() + "]:" + data2);

            urlConnection.setFixedLengthStreamingMode(data.length() + gpx2.length + data2.length());

            DataOutputStream outputStream = new DataOutputStream( urlConnection.getOutputStream() );
            outputStream.writeBytes(data);
            outputStream.write(gpx2);
            outputStream.writeBytes(data2);

            Log.d(TAG, "outputStream.size():" + outputStream.size() + " - gpx.length:" + gpx.length());
            // finished with POST request body

            outputStream.flush();
            outputStream.close();

            // checks server's status code first
            // Responses from the server (code and message)
            result.serverResponseCode = urlConnection.getResponseCode();
            result.serverResponseMessage = urlConnection.getResponseMessage();

            //Log.d(TAG, "_upload: " + result.serverResponseCode + "[" + result.serverResponseMessage + "]");

            result.message = result.serverResponseMessage + " (" + result.serverResponseCode + ")";

            //start listening to the stream
            String response = "";
            // Strava doc: Upon a successful submission the request will return 201 Created. If there was an error the request will return 400 Bad Request.
            InputStream is = null;
            if (result.serverResponseCode == 201) {
                is = urlConnection.getInputStream();
                result.message = "Your activity has been created";
                _parseAnalytics.trackEvent("strava_ok");
            } else if (result.serverResponseCode == 400) {
                 // {"id":11111111,"external_id":"file1465247568.gpx","error":"file1465247568.gpx duplicate of activity 222222","status":"There was an error processing your activity.","activity_id":null}
                is = urlConnection.getErrorStream();
                result.message = "An error has occurred. If you've already uploaded the current activity, please delete it in Strava.";
                _parseAnalytics.trackEvent("strava_error");
            } else if (result.serverResponseCode == 401) {
                // {"message":"Authorization Error","errors":[{"resource":"Athlete","field":"access_token","code":"invalid"}]}
                is = urlConnection.getErrorStream();
                result.message = "Error - Unauthorized. Please check your credentials in the settings.";
                _parseAnalytics.trackEvent("strava_unauthorized");
            }
            if (is != null) {
                Scanner inStream = new Scanner(is);
                //process the stream and store it in StringBuilder
                while (inStream.hasNextLine()) {
                    response += (inStream.nextLine()) + "\n";
                }
            }
            Log.d(TAG, "response:" + response);
            if (response != "") {
                try {
                    JSONObject jObject = new JSONObject(response);
                    //int strava_id = jObject.getInt("id");
                    //Log.d(TAG, "strava_id:" + strava_id);
                    ///@todo save strava_id to later check status

                    result.status = jObject.getString("status");
                    Log.d(TAG, "result.status:" + result.status);
                    result.error = jObject.getString("error");
                    if (result.serverResponseCode == 400) {

                        Pattern pattern = Pattern.compile("duplicate of activity ([0-9]+)");
                        Matcher match = pattern.matcher(result.error);
                        if (match.find()) {
                            result.duplicate_activity_id = Integer.valueOf(match.group(1));
                        }
                    }
                } catch (JSONException e) {
                    Log.e(TAG, "Exception:" + e);
                }
            }
            urlConnection.disconnect();
        } catch (Exception e) {
            Log.e(TAG, "Exception:" + e);
            //Toast.makeText(_context, "Exception:" + e, Toast.LENGTH_LONG).show();
            //result.message = "" + e;
        }
        return result;
    }
    private ApiResult _delete(String strava_token, int activity_id) {
        ApiResult result = new ApiResult();
        String tmp_url = "https://www.strava.com/api/v3/activities/" + activity_id;
        Log.d(TAG, "DELETE url="+tmp_url);
        try {
            URL url = new URL(tmp_url);
            HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
            String auth = "Bearer " + strava_token;
            //Log.d(TAG, "auth="+auth);
            urlConnection.setRequestProperty("Authorization", auth);
            urlConnection.setDoOutput(true);
            urlConnection.setRequestMethod("DELETE");
            urlConnection.connect();

            result.serverResponseCode = urlConnection.getResponseCode();
            result.serverResponseMessage = urlConnection.getResponseMessage();

            // Returns 204 No Content on success.
            //Log.d(TAG, "_delete: " + result.serverResponseCode + "[" + result.serverResponseMessage + "]");

            urlConnection.disconnect();
        } catch (Exception e) {
            Log.e(TAG, "Exception:" + e);
            //Toast.makeText(_context, "Exception:" + e, Toast.LENGTH_LONG).show();
            //result.message = "" + e;
        }
        return result;
    }
    public static byte[] compressString(String string) throws IOException {
        ByteArrayOutputStream os = new ByteArrayOutputStream(string.length());
        GZIPOutputStream gos = new GZIPOutputStream(os);
        gos.write(string.getBytes());
        gos.close();
        return os.toByteArray();
    }
}
