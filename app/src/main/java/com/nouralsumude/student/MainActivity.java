package com.nouralsumude.student;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity {
    private static final String SHELL_URL = "file:///android_asset/index.html";
    private static final int FILE_REQUEST = 4107;
    private WebView webView;
    private ProgressBar pageProgress;
    private ValueCallback<Uri[]> fileCallback;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean trustedShell = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(23,63,68));
        getWindow().setNavigationBarColor(Color.rgb(23,63,68));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(245,248,247));
        webView = new WebView(this);
        pageProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pageProgress.setMax(100);
        pageProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.rgb(245,198,90)));
        root.addView(webView, new FrameLayout.LayoutParams(-1,-1));
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(-1,6);
        pp.gravity = android.view.Gravity.TOP;
        root.addView(pageProgress, pp);
        setContentView(root);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setUserAgentString(s.getUserAgentString()+" StudentSamedOfflineAndroid/1.0");
        webView.addJavascriptInterface(new OfflineBridge(), "AndroidOffline");

        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                trustedShell = url != null && url.startsWith(SHELL_URL);
                super.onPageStarted(view,url,favicon);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri uri=req.getUrl(); String scheme=uri.getScheme()==null?"":uri.getScheme();
                if ("file".equals(scheme)) return false;
                if ("https".equals(scheme) && "www.youtube-nocookie.com".equals(uri.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW,uri)); } catch(Exception ignored) {}
                return true;
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if(req.isForMainFrame() && !String.valueOf(req.getUrl()).startsWith("file:")) {
                    Toast.makeText(MainActivity.this,"تعذر فتح الرابط. المحتوى المحمّل ما زال متاحًا.",Toast.LENGTH_LONG).show();
                    view.loadUrl(SHELL_URL);
                }
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel(); Toast.makeText(MainActivity.this,"تعذر التحقق من أمان الاتصال",Toast.LENGTH_LONG).show();
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view,int value) { pageProgress.setProgress(value); pageProgress.setVisibility(value<100?View.VISIBLE:View.GONE); }
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if(fileCallback!=null) fileCallback.onReceiveValue(null); fileCallback=callback;
                try { startActivityForResult(params.createIntent(),FILE_REQUEST); return true; }
                catch(Exception e){ fileCallback=null; return false; }
            }
        });
        if(state!=null) webView.restoreState(state); else webView.loadUrl(SHELL_URL);
    }

    private File unitsRoot(){ File root=new File(getFilesDir(),"units"); if(!root.exists()) root.mkdirs(); return root; }
    private boolean validId(String id){ return id!=null && id.matches("[a-zA-Z0-9_-]{1,60}"); }
    private boolean validEntry(String entry){ return entry!=null && entry.matches("[a-zA-Z0-9_./-]{1,100}") && !entry.contains(".."); }
    private boolean trusted(){ return trustedShell; }

    private void callback(String id,String state,int value,String message){
        String js="window.onNativeDownload("+JSONObject.quote(id)+","+JSONObject.quote(state)+","+value+","+JSONObject.quote(message==null?"":message)+")";
        runOnUiThread(()->webView.evaluateJavascript(js,null));
    }

    private String sha256(File file) throws Exception {
        MessageDigest md=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new BufferedInputStream(new FileInputStream(file))){ byte[] b=new byte[16384]; int n; while((n=in.read(b))!=-1) md.update(b,0,n); }
        StringBuilder out=new StringBuilder(); for(byte b:md.digest()) out.append(String.format(Locale.US,"%02x",b)); return out.toString();
    }

    private void unzipSafe(File zip,File destination) throws Exception {
        String root=destination.getCanonicalPath()+File.separator;
        try(ZipInputStream zin=new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))){
            ZipEntry entry; byte[] buf=new byte[16384];
            while((entry=zin.getNextEntry())!=null){
                File target=new File(destination,entry.getName()); String canonical=target.getCanonicalPath();
                if(!canonical.startsWith(root)) throw new SecurityException("ملف ZIP غير آمن");
                if(entry.isDirectory()){ if(!target.exists()&&!target.mkdirs()) throw new Exception("تعذر إنشاء المجلد"); }
                else { File parent=target.getParentFile(); if(parent!=null&&!parent.exists()&&!parent.mkdirs()) throw new Exception("تعذر إنشاء المجلد"); try(BufferedOutputStream out=new BufferedOutputStream(new FileOutputStream(target))){ int n; while((n=zin.read(buf))!=-1) out.write(buf,0,n); } }
                zin.closeEntry();
            }
        }
    }

    private void deleteTree(File file){ if(file==null||!file.exists()) return; File[] children=file.listFiles(); if(children!=null) for(File c:children) deleteTree(c); file.delete(); }

    public class OfflineBridge {
        @JavascriptInterface public String getUnitState(String id,String expectedSha){
            if(!validId(id)) return "missing";
            File dir=new File(unitsRoot(),id), marker=new File(dir,".installed"), entry=new File(dir,"index.html");
            if(!marker.isFile()||!entry.isFile()) return "missing";
            try {
                StringBuilder value=new StringBuilder();
                try(InputStream in=new FileInputStream(marker)){ byte[] data=new byte[128]; int n; while((n=in.read(data))!=-1) value.append(new String(data,0,n,"UTF-8")); }
                return value.toString().trim().equalsIgnoreCase(expectedSha)?"installed":"update";
            } catch(Exception e){ return "update"; }
        }

        @JavascriptInterface public boolean isUnitInstalled(String id,String expectedSha){ return "installed".equals(getUnitState(id,expectedSha)); }

        @JavascriptInterface public void downloadUnit(String id,String source,String expectedSha,String entry){
            if(!trusted()||!validId(id)||!validEntry(entry)){ callback(id,"error",0,"بيانات الوحدة غير صالحة"); return; }
            executor.execute(()->{
                File temp=null,staging=null;
                try {
                    URL url=new URL(source); if(!"https".equals(url.getProtocol())||!"raw.githubusercontent.com".equals(url.getHost())||!url.getPath().startsWith("/mounir-eng/Nour-al-Sumude/")) throw new SecurityException("مصدر التنزيل غير معتمد");
                    HttpURLConnection c=(HttpURLConnection)url.openConnection(); c.setConnectTimeout(20000); c.setReadTimeout(30000); c.setInstanceFollowRedirects(true); c.setRequestProperty("User-Agent","StudentSamedOfflineAndroid/1.0"); c.connect();
                    if(c.getResponseCode()<200||c.getResponseCode()>=300) throw new Exception("تعذر الوصول إلى ملف الوحدة");
                    int total=c.getContentLength(); temp=new File(getCacheDir(),id+".zip.part"); int read=0,last=-1;
                    try(InputStream in=new BufferedInputStream(c.getInputStream()); BufferedOutputStream out=new BufferedOutputStream(new FileOutputStream(temp))){ byte[] b=new byte[16384]; int n; while((n=in.read(b))!=-1){ out.write(b,0,n); read+=n; int pct=total>0?Math.min(95,read*95/total):25; if(pct>=last+3){ last=pct; callback(id,"progress",pct,""); } } }
                    c.disconnect();
                    if(!sha256(temp).equalsIgnoreCase(expectedSha)) throw new SecurityException("فشل التحقق من سلامة الوحدة؛ أعد التنزيل");
                    callback(id,"extracting",100,""); staging=new File(unitsRoot(),id+".new"); deleteTree(staging); if(!staging.mkdirs()) throw new Exception("تعذر تجهيز مساحة الوحدة"); unzipSafe(temp,staging);
                    File entryFile=new File(staging,entry); if(!entryFile.isFile()) throw new Exception("ملف تشغيل الوحدة غير موجود");
                    try(FileOutputStream out=new FileOutputStream(new File(staging,".installed"))){ out.write(expectedSha.getBytes("UTF-8")); }
                    File destination=new File(unitsRoot(),id); deleteTree(destination); if(!staging.renameTo(destination)) throw new Exception("تعذر تثبيت الوحدة"); staging=null; callback(id,"done",100,"");
                } catch(Exception e){ callback(id,"error",0,e.getMessage()==null?"تعذر تنزيل الوحدة":e.getMessage()); }
                finally { if(temp!=null) temp.delete(); if(staging!=null) deleteTree(staging); }
            });
        }

        @JavascriptInterface public void openUnit(String id,String entry){
            if(!trusted()||!validId(id)||!validEntry(entry)) return; File file=new File(new File(unitsRoot(),id),entry); if(!file.isFile()){ callback(id,"error",0,"الوحدة غير مثبتة"); return; }
            runOnUiThread(()->webView.loadUrl(Uri.fromFile(file).toString()));
        }

        @JavascriptInterface public void deleteUnit(String id){ if(trusted()&&validId(id)) deleteTree(new File(unitsRoot(),id)); }
    }

    @Override protected void onSaveInstanceState(Bundle out){ webView.saveState(out); super.onSaveInstanceState(out); }
    @Override public void onBackPressed(){ if(!SHELL_URL.equals(webView.getUrl())) webView.loadUrl(SHELL_URL); else super.onBackPressed(); }
    @Override protected void onActivityResult(int request,int result,Intent data){ super.onActivityResult(request,result,data); if(request==FILE_REQUEST&&fileCallback!=null){ fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result,data)); fileCallback=null; } }
    @Override protected void onDestroy(){ executor.shutdownNow(); if(webView!=null){ webView.stopLoading(); webView.destroy(); } super.onDestroy(); }
}
