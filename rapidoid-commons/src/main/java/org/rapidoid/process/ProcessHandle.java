package org.rapidoid.process;

import org.rapidoid.annotation.Authors;
import org.rapidoid.annotation.Since;
import org.rapidoid.collection.Coll;
import org.rapidoid.commons.Arr;
import org.rapidoid.group.AbstractManageable;
import org.rapidoid.lambda.Lmbd;
import org.rapidoid.lambda.Operation;
import org.rapidoid.log.Log;
import org.rapidoid.u.U;
import org.rapidoid.util.Wait;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/*
 * #%L
 * rapidoid-commons
 * %%
 * Copyright (C) 2014 - 2017 Nikolche Mihajlovski and contributors
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

@Authors("Nikolche Mihajlovski")
@Since("5.3.0")
public class ProcessHandle extends AbstractManageable {

	private final static Set<ProcessHandle> ALL = Coll.synchronizedSet();

	private final static ProcessCrawlerThread CRAWLER = new ProcessCrawlerThread(ALL);

	private final ProcessParams params;

	private final String id;

	private final BlockingQueue<Object> input = new ArrayBlockingQueue<>(100);

	private final BlockingQueue<String> output = null;

	private final BlockingQueue<String> error = null;

	private final StringBuffer outBuffer = new StringBuffer();
	private final StringBuffer errBuffer = new StringBuffer();
	private final StringBuffer outAndErrBuffer = new StringBuffer();

	private final AtomicBoolean doneReadingOut = new AtomicBoolean();
	private final AtomicBoolean doneReadingErr = new AtomicBoolean();

	private volatile Process process;

	private volatile Date startedAt;
	private volatile Date finishedAt;

	ProcessHandle(ProcessParams params) {
		this.params = params;
		this.id = params.id() != null ? params.id() : UUID.randomUUID().toString();

		// keep reference to the handle, used by the crawler internally
		ALL.add(this);

		// register to the process group, if configured
		if (params.group() != null) {
			params.group().add(this);
		}

		setupIO();
	}

	private void setupIO() {
		Thread inputProcessor = new ProcessIOThread(this) {
			@Override
			void doIO() {
				writeAll(input, process.getOutputStream());
			}
		};

		inputProcessor.setDaemon(true);
		inputProcessor.start();

		Thread errorProcessor = new ProcessIOThread(this) {
			@Override
			void doIO() {
				try {
					BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
					readInto(reader, error, errBuffer, outAndErrBuffer);
				} finally {
					doneReadingErr.set(true);
				}
			}
		};

		errorProcessor.setDaemon(true);
		errorProcessor.start();

		Thread outputProcessor = new ProcessIOThread(this) {
			@Override
			void doIO() {
				try {
					BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
					readInto(reader, output, outBuffer, outAndErrBuffer);
				} finally {
					doneReadingOut.set(true);
				}
			}
		};

		outputProcessor.setDaemon(true);
		outputProcessor.start();
	}

	private static void writeAll(BlockingQueue<Object> input, OutputStream output) {
		while (!Thread.interrupted()) {
			try {
				Object obj = input.take();

				if (obj instanceof String) {
					String s = (String) obj;
					output.write(s.getBytes());

				} else if (obj instanceof byte[]) {
					byte[] b = (byte[]) obj;
					output.write(b);

				} else {
					throw U.rte("Unsupported input object type: " + obj);
				}

				output.flush();
			} catch (Exception e) {
				Log.error("Cannot write!", e);
			}
		}
	}

	private long readInto(BufferedReader reader, BlockingQueue<String> dest, StringBuffer... buffers) {
		long total = 0;

		try {
			String line;
			while ((line = reader.readLine()) != null) {
				try {
					if (dest != null) {
						dest.put(line);
					}

					if (params.printingOutput()) {
						U.print(params.linePrefix() + line);
					}

					for (StringBuffer buffer : buffers) {
						buffer.append(line + "\n");
					}

					total++;
				} catch (InterruptedException e) {
					throw new CancellationException();
				}
			}
		} catch (IOException e) {
			// can't read anymore (e.g. the stream was closed)
		}

		return total;
	}

	public synchronized BlockingQueue<Object> input() {
		return input;
	}

	public synchronized BlockingQueue<String> output() {
		return output;
	}

	public synchronized BlockingQueue<String> error() {
		return error;
	}

	public synchronized Process process() {
		return process;
	}

	public synchronized ProcessParams params() {
		return params;
	}

	public synchronized boolean isAlive() {
		return process != null && exitCode() == null;
	}

	public void receive(Operation<String> outputProcessor, Operation<String> errorProcessor) {
		String s;

		int grace = 1;

		do {
			if (outputProcessor != null) {
				while ((s = output().poll()) != null) {
					Lmbd.call(outputProcessor, s);
				}
			}

			if (errorProcessor != null) {
				while ((s = error().poll()) != null) {
					Lmbd.call(errorProcessor, s);
				}
			}

			U.sleep(10);

		} while (isAlive() || !doneReadingOut.get() || !doneReadingErr.get() || (--grace) >= 0);
	}

	public void print() {
		U.print(outAndError());
	}

	public synchronized String out() {
		return outBuffer.toString();
	}

	public synchronized String err() {
		return errBuffer.toString();
	}

	public synchronized String outAndError() {
		return outAndErrBuffer.toString();
	}

	synchronized void startProcess(ProcessParams params) {

		ProcessBuilder builder = new ProcessBuilder().command(params.command());

		if (params.in() != null) {
			builder.directory(params.in());
		}

		Date startingAt = new Date();

		Process process;
		try {
			process = builder.start();
		} catch (IOException e) {
			throw U.rte("Cannot start process: " + U.join(" ", params.command()));
		}

		this.startedAt = startingAt;
		this.finishedAt = null;
		this.doneReadingErr.set(false);
		this.doneReadingOut.set(false);

		attach(process);

		synchronized (CRAWLER) {
			if (CRAWLER.getState() == Thread.State.NEW) CRAWLER.start();
		}
	}

	private void attach(Process process) {
		this.process = process;
	}

	private synchronized Process requireProcess() {
		return process;
	}

	public ProcessHandle waitFor() {
		try {
			requireProcess().waitFor();
		} catch (InterruptedException e) {
			throw new CancellationException();
		}

		Wait.until(doneReadingOut);
		Wait.until(doneReadingErr);

		return this;
	}

	public ProcessHandle waitFor(long timeout, TimeUnit unit) {
		try {
			requireProcess().waitFor(timeout, unit);
		} catch (InterruptedException e) {
			throw new CancellationException();
		}

		// FIXME timeout
		Wait.until(doneReadingOut);
		Wait.until(doneReadingErr);

		return this;
	}

	public ProcessHandle destroy() {
		requireProcess().destroy();
		return this;
	}

	public ProcessHandle destroyForcibly() {
		requireProcess().destroyForcibly();
		return this;
	}

	public synchronized String cmd() {
		return params.command()[0];
	}

	public synchronized String[] args() {
		return Arr.sub(params.command(), 1, params().command().length);
	}

	public synchronized Integer exitCode() {
		try {
			return process != null ? process.exitValue() : null;
		} catch (IllegalThreadStateException e) {
			return null;
		}
	}

	public synchronized long duration() {
		if (this.startedAt == null) return 0;

		Date until = this.finishedAt;
		if (until == null) until = new Date();

		return until.getTime() - this.startedAt.getTime();
	}

	synchronized void onTerminated() {
		finishedAt = new Date();
	}

	public synchronized Date startedAt() {
		return startedAt;
	}

	public synchronized Date finishedAt() {
		return finishedAt;
	}

	@Override
	public synchronized String id() {
		return id;
	}

	@Override
	public synchronized List<String> actions() {
		List<String> actions = U.list("?Restart");

		if (isAlive()) {
			actions.add("!Terminate");
		}

		return actions;
	}

	public synchronized Processes group() {
		return params.group();
	}

	public synchronized ProcessHandle restart() {
		destroy();

		startProcess(params);

		return this;
	}

	public synchronized ProcessHandle terminate() {
		return destroy();
	}

}
