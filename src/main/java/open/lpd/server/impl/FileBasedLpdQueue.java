/**********************************************************************************

   Copyright 2014 thei71

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */
package open.lpd.server.impl;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import open.lpd.server.ILpdQueue;
import open.lpd.server.LpdServerProtocol;

public class FileBasedLpdQueue implements ILpdQueue {

	private static final byte ERR_QUEUE_DOES_NOT_EXIST = 1;
	private static final byte ERR_WRONG_CONTROL_FILE_NAME = 2;
	private static final byte ERR_WRONG_DATA_FILE_NAME = 3;

	private static long jobId = 0;

	private String queueFolderName;
	private String subCmdPrintJob;
	private String subCmdQueue;
	private String scriptCmd;

	public FileBasedLpdQueue(String queueFolderName, String scriptCmd) {
		this.queueFolderName = queueFolderName;
		this.scriptCmd = scriptCmd;
		this.subCmdPrintJob = null;
		this.subCmdQueue = null;
	}

	@Override
	public void printAnyWaitingJobs(String queue) throws IOException {

		// protocol command implementation

		if (queueExists(queue)) {
			for (File printJobFolder : new File(queueFolderName, queue)
					.listFiles()) {
				runScript(queue, printJobFolder);
			}
		}
	}

	@Override
	public byte receiveAPrinterJob(String queue) throws IOException {

		// protocol command implementation

		if (!queueExists(queue)) {
			return ERR_QUEUE_DOES_NOT_EXIST;
		}
		subCmdQueue = queue;
		synchronized (FileBasedLpdQueue.class) {
			subCmdPrintJob = new Date().getTime() + "-" + String.valueOf(jobId);
			jobId++;
		}
		return LpdServerProtocol.ACK_SUCCESS;
	}

	@Override
	public String sendQueueStateShort(String queue, String[] list)
			throws IOException {

		// protocol command implementation

		StringBuilder sb = new StringBuilder();
		if (queueExists(queue)) {
			List<String> jobList = new ArrayList<String>();
			if (list != null) {
				for (String listEntry : list) {
					jobList.add(listEntry);
				}
			}
			for (File printJobFolder : new File(queueFolderName, queue)
					.listFiles()) {
				if (!jobList.contains(printJobFolder.getName())
						&& jobList.size() > 0) {
					continue;
				}
				sb.append(printJobFolder.getName());
				sb.append("\n");
			}
		} else {
			sb.append("Queue " + queue + " does not exist.");
		}
		return sb.toString();
	}

	@Override
	public String sendQueueStateLong(String queue, String[] list)
			throws IOException {

		// protocol command implementation

		StringBuilder sb = new StringBuilder();
		if (queueExists(queue)) {
			List<String> jobList = new ArrayList<String>();
			if (list != null) {
				for (String listEntry : list) {
					jobList.add(listEntry);
				}
			}
			for (File printJobFolder : new File(queueFolderName, queue)
					.listFiles()) {
				if (!jobList.contains(printJobFolder.getName())
						&& jobList.size() > 0) {
					continue;
				}
				sb.append(printJobFolder.getName());
				sb.append("\t");
				sb.append(new Date(printJobFolder.lastModified()).toString());
				sb.append("\t");
				for (File printJobFile : printJobFolder.listFiles()) {
					sb.append(printJobFile.getName());
					sb.append(" (");
					sb.append(printJobFile.length());
					sb.append(" byte) ");
				}
				sb.append("\n");
			}
		} else {
			sb.append("Queue " + queue + " does not exist.");
		}
		return sb.toString();
	}

	@Override
	public void removeJobs(String queue, String agent, String[] list)
			throws IOException {

		// protocol command implementation

		if (queueExists(queue)) {
			for (File printJobFolder : new File(queueFolderName, queue)
					.listFiles()) {
				for (String name : list) {
					if (printJobFolder.getName().equals(name)
							&& printJobFolder.isDirectory()) {
						removePrintJobFolder(printJobFolder);
					}
				}
			}
		}
	}

	@Override
	public void abortJob() throws IOException {

		// protocol sub command implementation

		if (queueExists(subCmdQueue)) {
			File printJobFolder = new File(new File(queueFolderName,
					subCmdQueue), subCmdPrintJob);
			if (printJobFolder.exists()) {
				removePrintJobFolder(printJobFolder);
			}
		}
	}

	@Override
	public byte receiveControlFile(int count, String name, InputStream is)
			throws IOException {

		// protocol sub command implementation

		if (!name.startsWith("cfA")) {
			return ERR_WRONG_CONTROL_FILE_NAME;
		}
		if (queueExists(subCmdQueue)) {
			receiveFile(subCmdQueue, subCmdPrintJob, count, name, is);
			return LpdServerProtocol.ACK_SUCCESS;
		} else {
			return ERR_QUEUE_DOES_NOT_EXIST;
		}
	}

	@Override
	public byte receiveDataFile(int count, String name, InputStream is)
			throws IOException {

		// protocol sub command implementation

		if (!name.startsWith("dfA")) {
			return ERR_WRONG_DATA_FILE_NAME;
		}
		if (queueExists(subCmdQueue)) {
			receiveFile(subCmdQueue, subCmdPrintJob, count, name, is);
			return LpdServerProtocol.ACK_SUCCESS;
		} else {
			return ERR_QUEUE_DOES_NOT_EXIST;
		}
	}

	private File receiveFile(String queue, String printJob, int count,
			String name, InputStream is) throws IOException {

		// file name sanity check

		checkFileName(name);

		// create print job folder if it does not exist

		File printJobFolder = createPrintJobFolder(printJob, new File(
				queueFolderName, queue));

		// check if file exists in print job folder

		File file = new File(printJobFolder, name);
		if (file.exists()) {
			throw new IOException(
					"File already exists in print job folder, name: " + name);
		}

		// receive file to print job folder

		FileOutputStream fos = null;
		BufferedOutputStream bos = null;
		try {
			fos = new FileOutputStream(file);
			bos = new BufferedOutputStream(fos);
			readBinary(is, bos, count);
		} catch (SocketTimeoutException e) {
			if (count > 0) {
				throw e;
			}
			// just close gracefully
		} finally {
			closeQuietly(bos);
			fos.close();
		}
		return file;
	}

	private File createPrintJobFolder(String printJob, File queueFolder)
			throws IOException {

		// create the print job folder in the queue folder to hold all files
		// related to that print job

		File printJobFolder = new File(queueFolder, printJob);
		if (!printJobFolder.exists()) {
			if (!printJobFolder.mkdir()) {
				throw new IOException(
						"Print job folder could not be created, printJob: "
								+ printJob);
			}
		}
		return printJobFolder;
	}

	private void readBinary(InputStream is, OutputStream os, int count)
			throws IOException {

		// read a binary file from a stream

		int bytesRead = 0;
		while ((count > 0) && (bytesRead < count)) {
			int c = is.read();
			if (c == -1) {
				break;
			}
			os.write(c);
			bytesRead++;
		}
	}

	private boolean queueExists(String queue) {

		// check if queue was specified and exists as a folder

		if (queue == null) {
			return false;
		}
		File queueFolder = new File(queueFolderName, queue);
		return queueFolder.exists();
	}

	private void checkFileName(String name) throws IOException {

		// file name sanity check

		if (name.contains("..") || name.contains("?") || name.contains(":")) {
			throw new IOException("Invalid file name: " + name);
		}
	}

	private void removePrintJobFolder(File printJobFolder) {

		// delete all files in print job folder

		for (File file : printJobFolder.listFiles()) {
			file.delete();
		}

		// delete print job folder itself

		printJobFolder.delete();
	}

	private void closeQuietly(Closeable closeable) {

		// ensure closed quietly

		try {
			if (closeable != null) {
				closeable.close();
			}
		} catch (IOException e) {
			// ignore quietly
		}
	}

	public String runScript(String queue, File printJobFolder)
			throws IOException {

		// run a configured OS specific script file to deal with the print job

		String[] args = new String[2];
		args[0] = queue;
		args[1] = printJobFolder.getAbsoluteFile().toPath().toString();
		String processExecutable = scriptCmd;
		String processParams = null;
		if (processExecutable.startsWith("\"")) {
			int k = processExecutable.indexOf("\"", 1);
			if (k >= 0) {
				processParams = processExecutable.substring(k + 2);
				processExecutable = processExecutable.substring(0, k + 1);
			}
		} else {
			int k = processExecutable.indexOf(" ");
			if (k >= 0) {
				processParams = processExecutable.substring(k + 1);
				processExecutable = processExecutable.substring(0, k);
			}
		}
		if (processParams != null) {
			for (int i = 0; i < args.length; i++) {
				int k = processParams.indexOf("$" + (i + 1));
				if (k >= 0) {
					processParams = processParams.substring(0, k) + args[i]
							+ processParams.substring(k + 2);
				}
			}
		}
		return runProcess(processExecutable, processParams);
	}

	private String runProcess(String processExecutable, String processParams)
			throws IOException {

		// run OS process

		InputStream processOutput;
		Process process;
		if (processParams == null) {
			process = Runtime.getRuntime().exec(
					new String[] { processExecutable });
		} else {
			String[] paramsArray = processParams.split(" ");
			String[] cmdArray = new String[paramsArray.length + 1];
			cmdArray[0] = processExecutable;
			for (int i = 0; i < paramsArray.length; i++) {
				cmdArray[1 + i] = paramsArray[i];
			}
			process = Runtime.getRuntime().exec(cmdArray);
		}

		// collect process output and return

		processOutput = process.getInputStream();
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		while (true) {
			int c = processOutput.read();
			if (c == -1) {
				break;
			}
			bos.write((byte) c);
		}

		// create string using platform encoding

		return new String(bos.toByteArray());
	}

	@Override
	public void finishedReceivingAPrinterJob() throws IOException {

		if (queueExists(subCmdQueue)) {
			File queueFolder = new File(queueFolderName, subCmdQueue);
			File printJobFolder = new File(queueFolder, subCmdPrintJob);
			if (printJobFolder.exists() && printJobFolder.isDirectory()) {
				runScript(subCmdQueue, printJobFolder);
			}
		}
	}
}
