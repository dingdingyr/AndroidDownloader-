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


import android.util.Log;

import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;


/**
 * Usage The download thread.
 * @author yinglovezhuzhu@gmail.com
 *
 */
public class DownloadThread extends Thread {

	private static final String TAG = "DOWNLOADER";
	
	private static final int BUFFER_SIZE = 1024 * 8;

	private Downloader mDownloader;
	private URL mUrl;
	private File mSavedFile;
	private int mBlockSize;
	private int mDownloadedSize;
	private int mThreadId = -1;

	private boolean mFinished = false;

	/**
	 * 构造方法
	 * 
	 * @param downloader Downloader instance.
	 * @param downUrl The url of downloading file
	 * @param saveFile The local file that to save the downloading file.
	 * @param block The block size that this thread need to download.
	 * @param downloadedLength The length this thread finish download, if(downloadLength<block) means this thread are not finished.
	 * @param threadId The id of this thread.
	 */
	public DownloadThread(Downloader downloader, URL downUrl, File saveFile, int block, int downloadedLength, int threadId) {
		this.mUrl = downUrl;
		mSavedFile = saveFile;
		this.mBlockSize = block;
		this.mDownloader = downloader;
		mThreadId = threadId;
		this.mDownloadedSize = downloadedLength;
	}

	@Override
	public void run() {
		if (mDownloadedSize < mBlockSize) {// If this thread are not finished.
			try {
				HttpURLConnection conn = (HttpURLConnection) mUrl.openConnection();
				conn.setConnectTimeout(6 * 1000);
				conn.setRequestMethod("GET");
				conn.setRequestProperty("Accept", "*/*"); // accept all MIME-TYPE
				conn.setRequestProperty("Accept-Language", "zh-CN");
				conn.setRequestProperty("Referer", mUrl.toString());
				conn.setRequestProperty("Charset", "UTF-8");

				// Get the position of this thread start to download.
				int startPos = mBlockSize * (mThreadId - 1) + mDownloadedSize;
				// Get the position of this thread end to download.
				int endPos = mBlockSize * mThreadId - 1;

                //Setting the rage of the data, it will return exact realistic size automatically,
                // if the size set to be is lager then realistic size.
				conn.setRequestProperty("Range", "bytes=" + startPos + "-" + endPos);

				// Client agent
				conn.setRequestProperty("User-Agent",
						"Mozilla/4.0 (compatible; MSIE 8.0;"
								+ " Windows NT 5.2; Trident/4.0;"
								+ " .NET CLR 1.1.4322;"
								+ " .NET CLR 2.0.50727;"
								+ " .NET CLR 3.0.04506.30;"
								+ " .NET CLR 3.0.4506.2152;"
								+ " .NET CLR 3.5.30729)");

				// Use long connection.
				conn.setRequestProperty("Connection", "Keep-Alive");
				// Get the input stream of the connection.
				InputStream inStream = conn.getInputStream();
				// Set local cache size
				byte[] buffer = new byte[BUFFER_SIZE];
				int offset = 0;
				Log.i(TAG, mThreadId + " starts to download from position " + startPos);
				RandomAccessFile threadFile = new RandomAccessFile(mSavedFile, "rwd");
				// Make the pointer point to the position where start to download.
				threadFile.seek(startPos);
                // The data is written to file until user stop download or data is finished download.
				while (!mDownloader.isStop() && (offset = inStream.read(buffer)) != -1) {
					threadFile.write(buffer, 0, offset);
					mDownloadedSize += offset;
					// Update the range of this thread to database.
					mDownloader.update(mThreadId, mDownloadedSize);
					// Update the size of downloaded.
					mDownloader.append(offset);
				}
				threadFile.close();
				inStream.close();

				if (mDownloader.isStop()) {
					Log.i(TAG, "Download thread " + mThreadId + " has been paused");
				} else {
					Log.i(TAG, "Download thread " + mThreadId + " has been finished");
				}
				this.mFinished = true; // 设置完成标志为true，无论是下载完成还是用户主动中断下载
			} catch (Exception e) {
				// Set downloaded size to -1.
				this.mDownloadedSize = -1;
				Log.e(TAG, "Thread " + mThreadId + ":" + e);
			}
		}
	}

	/**
	 * Get the download state,finished or not.
	 * 
	 * @return true if this thread is finished.
	 */
	public boolean isFinished() {
		return mFinished;
	}

	/**
	 * Get the size of downloaded.
	 * 
	 * @return The size of downloaded,return -1 when this thread download failed
	 */
	public long getDownloadedLength() {
		return mDownloadedSize;
	}
}
