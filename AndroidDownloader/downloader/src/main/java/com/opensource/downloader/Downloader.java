/*
 * Copyright (C) 2014 The Android Open Source Project.
 *
 *        yinglovezhuzhu@gmail.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.opensource.downloader;

import android.content.Context;
import android.util.Log;

import com.opensource.downloader.db.DownloadLogDBUtils;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * usage Downloader class
 * @author yinglovezhuzhu@gmail.com
 *
 */
public class Downloader {
	
	private static final String TAG = "DOWNLOADER";
	
	private static final String TEMP_FILE_SUFFIX = ".download";
	
	private static final int RESPONSE_OK = 200;
	private Context mContext;
	private boolean mStop = true; // The flag of stopped.
	private int mDownloadedSize = 0; // The size of downloaded.
	private int mFileSize = 0; // The size of the file which to download.
	private DownloadThread [] mTheadPool; // The thread pool of download thread.
    private File mSaveFolder;
	private File mSavedFile; // The local file.
	private File mDownloadedFile;
	private String mFileName; // save file name;
	private long mUpdateTime = 1000;
	private Map<Integer, Integer> mData = new ConcurrentHashMap<Integer, Integer>(); // The data of all thread state
	private int mBlockSize; // The block size of each thread need to download
	private String mUrl; // The url of the file which to download.
	
	private boolean mFinished = false;
	
	private boolean mBreakPointSupported = true;

	/**
	 * Constructor<br><br>
	 * @param context
	 * @param downloadUrl
	 * @param saveFolder
     * @param fileName 保存文件名称
	 * @param threadNum
	 */
	public Downloader(Context context, String downloadUrl, File saveFolder, String fileName, int threadNum) {
        this.mContext = context;
        this.mUrl = downloadUrl;
        this.mSaveFolder = saveFolder;
        this.mTheadPool = new DownloadThread[threadNum];
        this.mFileName = fileName;

        checkDownloadFolder(saveFolder);
    }
	
	/**
	 * Constructor<br><br>
	 * @param context
	 * @param downloadUrl
	 * @param saveFolder
     * @param fileName 保存的文件名称
	 * @param threadNum
	 * @param breakPointSupported Is break point supporeted, If this value is true, it would save the download log into database.
	 */
	public Downloader(Context context, String downloadUrl, File saveFolder, String fileName, int threadNum, boolean breakPointSupported) {
		this(context, downloadUrl, saveFolder, fileName, threadNum);
		this.mBreakPointSupported = breakPointSupported;
	}

	/**
	 * Download file，this method has network, don't use it on ui thread.
	 * 
	 * @param listener The listener to listen download state, can be null if not need.
	 * @return The size that downloaded.
	 * @throws Exception The error happened when downloading.
	 */
	public File download(DownloadListener listener) throws Exception {
        mStop = false;
        HttpURLConnection conn = null;
        try {
            conn = getConnection(mUrl);

            if (conn.getResponseCode() == RESPONSE_OK) {
                mFileSize = conn.getContentLength();
                // Throw a RuntimeException when got file size failed.
                if (mFileSize <= 0) {
                    throw new RuntimeException("Can't get file size ");
                }

                if(null == mFileName || mFileName.length() < 1) {
                    String filename = getFileName(conn);
                    // Create local file object according to local saved folder and local file name.
                    mSavedFile = new File(mSaveFolder, filename);
                } else {
                    mSavedFile = new File(mSaveFolder, mFileName);
                }

                if(mBreakPointSupported) {
                	Map<Integer, Integer> logData = DownloadLogDBUtils.getLogByUrl(mContext, mUrl);
                	if (!logData.isEmpty()) {
                		for (Map.Entry<Integer, Integer> entry : logData.entrySet()) {
                			mData.put(entry.getKey(), entry.getValue());
                		}
                	}
                }

                // If the number of threads that have been downloaded data and the number
                // of threads is the same now set all the threads have calculated
                // the total length of the downloaded data
                mDownloadedSize = getDownloadedSize();
                // Calculate the length of each thread need to download.
                mBlockSize = getBlockSize(mFileSize, mTheadPool.length);
            } else {
                Log.w(TAG, "Server response error! Response code：" + conn.getResponseCode()
						+ "Response message：" + conn.getResponseMessage());
                throw new RuntimeException("server response error, response code:" + conn.getResponseCode());
            }
        } catch (Exception e) {
            Log.e(TAG, e.toString());
            throw new RuntimeException("Failed to connect the url:" + mUrl, e);
        } finally {
            if(null != conn) {
                conn.disconnect();
            }
        }

        if(null == mSavedFile) {
            mStop = true;
            return null;
        }

        // Mark a downloading file name a suffix flag,
        // so as not to open the unfinished download files and error
        mSavedFile = new File(mSavedFile.getAbsolutePath() + TEMP_FILE_SUFFIX);

		try {
			RandomAccessFile randOut = new RandomAccessFile(mSavedFile, "rwd");
			if (mFileSize > 0) {
				randOut.setLength(mFileSize); // Set total size of the download file.
			}
			randOut.close(); // Close the RandomAccessFile to make the settings effective
			URL url = new URL(mUrl);
			if (mData.size() != mTheadPool.length) { // The thread count in download log not equal to now.
				mData.clear();
				for (int i = 0; i < mTheadPool.length; i++) {
					mData.put(i + 1, 0);// Init download state map
				}
				mDownloadedSize = 0; // Init downloaded size.
			}
			for (int i = 0; i < mTheadPool.length; i++) {
				int downloadedLength = mData.get(i + 1); // Get the size of downloaded from each thread.
				if (downloadedLength < mBlockSize && mDownloadedSize < mFileSize) {// Go through when downloaded size less then total size.
					mTheadPool[i] = new DownloadThread(this, url, mSavedFile, mBlockSize, mData.get(i + 1), i + 1); // Init the thread with the given id
					mTheadPool[i].setPriority(7); // Set the priority of thread
					                              // Thread.NORM_PRIORITY = 5
					                              // Thread.MIN_PRIORITY = 1
					                              // Thread.MAX_PRIORITY = 10
					mTheadPool[i].start(); // Start thread
					mFinished = false;
				} else {
					mTheadPool[i] = null; // The thread is finished
				}
			}
			
			if(mBreakPointSupported) {
				DownloadLogDBUtils.delete(mContext, mUrl); // delete all download log
				DownloadLogDBUtils.save(mContext, mUrl, mSavedFile.getAbsolutePath(), mData); // add new download log
			}

            boolean isDownloading = false;
            do {
            	isDownloading = false;
                for (int i = 0; i < mTheadPool.length; i++) {
                    if (mTheadPool[i] != null && !mTheadPool[i].isFinished()) {// If has some thread not finished.
                        isDownloading = true;// Set is download state not finished.
                        mFinished = false;
                        if (mTheadPool[i].getDownloadedLength() == -1) {
                            mTheadPool[i] = new DownloadThread(this, url, mSavedFile, mBlockSize, mData.get(i + 1), i + 1); // 重新开辟下载线程
                            mTheadPool[i].setPriority(7);
                            mTheadPool[i].start();
                        }
                    }
                }
                if (listener != null) {
                    listener.onDownloadSize(mFileSize, mDownloadedSize);// download state call back
                                                                        // return then download size and downloaded size.
                }
                if(mFileSize == mDownloadedSize) {
                    isDownloading = false;
                } else {
                	try {
                		Thread.sleep(mUpdateTime);
                	} catch (InterruptedException e) {
                		isDownloading = false;
                	}
                }
            } while(isDownloading);
			if (mDownloadedSize == mFileSize) {
                String fileName = mSavedFile.getAbsolutePath();
                fileName = fileName.substring(0, fileName.indexOf(TEMP_FILE_SUFFIX));
                mDownloadedFile = new File(fileName);
                mSavedFile.renameTo(mDownloadedFile);
                if(mBreakPointSupported) {
                	DownloadLogDBUtils.delete(mContext, mUrl);// Delete download log when finished download
                }
				mFinished = true;
			}
		} catch (Exception e) {
			Log.e(TAG, e.toString());// 打印错误
			throw new Exception("Exception occured when downloading file\n", e);// Throw exception when some error happened when downloading.
		}
		return mDownloadedFile;
	}


	/**
	 * Get download state is finished or not.
	 * @return
	 */
	public boolean isFinished() {
		return mFinished;
	}

	/**
	 * Get download thread count.
	 */
	public int getThreadNum() {
		return mTheadPool.length;
	}

	/**
	 * Stop the download
	 */
	public synchronized void stop() {
		this.mStop = true;
	}

	/**
	 * Get download state is stopped or not.
	 * @return
	 */
	public synchronized boolean isStop() {
		return this.mStop;
	}

	/**
	 * Get total file size
	 * 
	 * @return
	 */
	public int getFileSize() {
		return mFileSize;
	}

	/**
	 * Set update frequency
	 * @param updateTime
	 */
	public void setUpdateTime(long updateTime) {
		this.mUpdateTime = updateTime;
	}

	/**
	 * Update downloaded size.
	 * 
	 * @param size
	 */
	protected synchronized void append(int size) {
		mDownloadedSize += size;
	}

	/**
	 * Update the download state by thread id.
	 * 
	 * @param threadId thread id
	 * @param pos The last position downloaded.
	 */
	protected synchronized void update(int threadId, int pos) {
		mData.put(threadId, pos); // Update map data.
		if(mBreakPointSupported) {
			DownloadLogDBUtils.update(mContext, mUrl, mSavedFile.getAbsolutePath(), threadId, pos); // Update database data.
		}
	}

	/**
	 * Get HttpConnection object
	 * @param downloadUrl the url to download.
	 * @return HttpConnection object
	 */
	private HttpURLConnection getConnection(String downloadUrl) throws IOException {
		URL url = new URL(downloadUrl);
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setConnectTimeout(5 * 1000);
		conn.setRequestMethod("GET");
		conn.setRequestProperty("Accept", "*/*");
		conn.setRequestProperty("Accept-Language", "zh-CN");
		conn.setRequestProperty("Referer", downloadUrl);
		conn.setRequestProperty("Charset", "UTF-8");
		// Set agent.
		conn.setRequestProperty("User-Agent", "Mozilla/4.0 (compatible; "
				+ "MSIE 8.0; Windows NT 5.2;"
				+ " Trident/4.0; .NET CLR 1.1.4322;"
				+ ".NET CLR 2.0.50727; " + ".NET CLR 3.0.04506.30;"
				+ " .NET CLR 3.0.4506.2152; " + ".NET CLR 3.5.30729)");
		conn.setRequestProperty("Connection", "Keep-Alive");
		conn.connect();
		Log.i(TAG, getResponseHeader(conn));
		return conn;
	}
	
	/**
	 * Check the download folder, make new folder if it is not exist.
	 * @param folder
	 */
	private void checkDownloadFolder(File folder) {
		if (!folder.exists()) {
			folder.mkdirs();
		}
	}
	
	/**
	 * Get the block size.
	 * @param fileSize The size of the file which to download.
	 * @param blockNum The amount of block.
	 * @return
	 */
	private int getBlockSize(int fileSize, int blockNum) {
		return fileSize % blockNum == 0 ? fileSize / blockNum
				: fileSize / blockNum + 1;
	}
	
	/**
     * Get downloaded size.
	 * @return
	 */
	private int getDownloadedSize() {
		int size = 0;
		if (mData.size() == mTheadPool.length) {
			Set<Integer> keys = mData.keySet();
			for (Integer key : keys) {
				size += mData.get(key);
			}
			Log.i(TAG, "Downloaded size " + size + " bytes");
		}
		return size;
	}

	/**
	 * Get file name
	 * @param conn HttpConnection object
	 * @return
	 */
	private String getFileName(HttpURLConnection conn) {
		String filename = mUrl.substring(mUrl.lastIndexOf("/") + 1);

		if (null == filename || filename.length() < 1) {// Get file name failed.
			for (int i = 0;; i++) { // Get file name from http header.
				String mine = conn.getHeaderField(i);
				if (mine == null)
					break; // Exit the loop when go through all http header.
				if ("content-disposition".equals(conn.getHeaderFieldKey(i).toLowerCase(Locale.ENGLISH))) { // Get content-disposition header field returns, which may contain a file name
					Matcher m = Pattern.compile(".*filename=(.*)").matcher(mine.toLowerCase(Locale.ENGLISH)); // Using regular expressions query file name
					if (m.find()) {
						return m.group(1); // If there is compliance with the rules of the regular expression string
					}
				}
			}
			filename = UUID.randomUUID() + ".tmp";// A 16-byte binary digits generated by a unique identification number
			                                      // (each card has a unique identification number)
			                                      // on the card and the CPU clock as the file name
		}
		return filename;
	}

	/**
	 * Get HTTP response header field
	 * 
	 * @param http HttpURLConnection object
	 * @return HTTp response header field map.
	 */
	private static Map<String, String> getHttpResponseHeader(HttpURLConnection http) {
		Map<String, String> header = new LinkedHashMap<String, String>();
		for (int i = 0;; i++) {
			String fieldValue = http.getHeaderField(i);
			if (fieldValue == null) {
				break;
			}
			header.put(http.getHeaderFieldKey(i), fieldValue);
		}
		return header;
	}

	/**
	 * Get HTTP response header field as a string
	 * @param conn HttpURLConnection object
     * @return HTTP response header field as a string
	 */
	private static String getResponseHeader(HttpURLConnection conn) {
		Map<String, String> header = getHttpResponseHeader(conn);
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, String> entry : header.entrySet()) {
			String key = entry.getKey() != null ? entry.getKey() + ":" : "";
			sb.append(key + entry.getValue());
		}
		return sb.toString();
	}
}
