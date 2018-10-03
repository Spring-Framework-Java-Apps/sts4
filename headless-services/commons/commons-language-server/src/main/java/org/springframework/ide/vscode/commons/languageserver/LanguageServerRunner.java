/*******************************************************************************
 * Copyright (c) 2016-2017 Pivotal, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Pivotal, Inc. - initial API and implementation
 *******************************************************************************/
package org.springframework.ide.vscode.commons.languageserver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.Channels;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

import javax.inject.Provider;

import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.MessageConsumer;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.ide.vscode.commons.languageserver.util.SimpleLanguageServer;
import org.springframework.ide.vscode.commons.util.Log;

/**
 * A CommandLineRunner that launches a language server. This meant to be used as a Spring bean
 * in a SpringBoot app.
 *
 * @author Kris De Volder
 * @author Martin Lippert
 */
public class LanguageServerRunner implements CommandLineRunner {

	final static Logger log = LoggerFactory.getLogger(LanguageServerRunner.class);

	@Override
	public void run(String... args) throws Exception {
		//TODO: feels a bit wasteful to have thread dedicated to just waiting for the server to stop.
		//  Not sure how we can really avoid this though. Lsp4j is providing
		//  lots of api that returns Futures which the only way to deal with them is blocking threads calling
		//  their get method.
		new Thread(
			() -> {
				try {
					start();
				} catch (Exception e) {
					log.error("", e);
				}
			},
			"LanguageServerApp lifecycle"
		).start();
	}

	/**
	 * System property that is set when the app launches. This makes it easy for the JVM process to recognized
	 * as a languageserver by using (for example) JMX to read the system properties.
	 */
	public static final String STS4_LANGUAGESERVER_NAME = "sts4.languageserver.name";

	public static final String STANDALONE_STARTUP = "standalone-startup"; //TODO: turn into spring boot property
	private static final int SERVER_STANDALONE_PORT = 5007;	//TODO: turn into spring boot property


	private final String name;
	private final Provider<SimpleLanguageServer> languageServerFactory;

	public LanguageServerRunner(String name, Provider<SimpleLanguageServer> languageServerFactory) {
		super();
		this.name = name;
		this.languageServerFactory = languageServerFactory;
	}

	public void start() throws Exception {
		System.setProperty(STS4_LANGUAGESERVER_NAME, name); //makes it easy to recognize language server processes.
		LanguageServerRunner app = this;
		if (System.getProperty(STANDALONE_STARTUP, "false").equals("true")) {
			app.startAsServer();
		} else {
			app.startAsClient();
		}
	}

	protected static class Connection {
		final InputStream in;
		final OutputStream out;
		final Socket socket;

		private Connection(InputStream in, OutputStream out, Socket socket) {
			this.in = in;
			this.out = out;
			this.socket = socket;
		}

		void dispose() {
			if (in != null) {
				try {
					in.close();
				} catch (IOException e) {
					Log.log(e);
				}
			}
			if (out != null) {
				try {
					out.close();
				} catch (IOException e) {
					Log.log(e);
				}
			}
			if (socket != null) {
				try {
					socket.close();
				} catch (IOException e) {
					Log.log(e);
				}
			}
		}
	}

	public void startAsClient() throws IOException {
		Log.info("Starting LS");
		Connection connection = null;
		try {
			connection = connectToNode();
			runAsync(connection).get();
		} catch (Throwable t) {
			Log.log(t);
			System.exit(1);
		} finally {
			if (connection != null) {
				connection.dispose();
			}
		}
	}

	/**
	 * starts up the language server and let it listen for connections from the outside
	 * instead of connecting itself to an existing port or channel.
	 *
	 * This is meant for development only, to reduce turnaround times while working
	 * on the language server from within an IDE, so that you can start the language
	 * server right away in debug mode and let the vscode extension connect to that
	 * instance instead of vice versa.
	 *
	 * Source of inspiration:
	 * https://github.com/itemis/xtext-languageserver-example/blob/master/org.xtext.example.mydsl.ide/src/org/xtext/example/mydsl/ide/RunServer.java
	 */
	public void startAsServer() throws Exception {
		log.info("Starting LS as standlone server port = {}", SERVER_STANDALONE_PORT);

		Function<MessageConsumer, MessageConsumer> wrapper = consumer -> {
			MessageConsumer result = consumer;
			return result;
		};

		SimpleLanguageServer languageServer = createServer();
		Launcher<STS4LanguageClient> launcher = createSocketLauncher(languageServer, STS4LanguageClient.class,
				new InetSocketAddress("localhost", SERVER_STANDALONE_PORT), createServerThreads(), wrapper);

		languageServer.connect(launcher.getRemoteProxy());
		launcher.startListening().get();
	}

	/**
	 * Creates the thread pool / executor passed to lsp4j server intialization. From the looks of things,
	 * @return
	 */
	protected ExecutorService createServerThreads() {
		return Executors.newCachedThreadPool();
	}

	private <T> Launcher<T> createSocketLauncher(
			Object localService, Class<T> remoteInterface,
			SocketAddress socketAddress, ExecutorService executorService,
			Function<MessageConsumer, MessageConsumer> wrapper
	) throws Exception {
		AsynchronousServerSocketChannel serverSocket = AsynchronousServerSocketChannel.open().bind(socketAddress);
		AsynchronousSocketChannel socketChannel = serverSocket.accept().get();
		log.info("Client connected via socket");
		return Launcher.createIoLauncher(localService, remoteInterface, Channels.newInputStream(socketChannel),
				Channels.newOutputStream(socketChannel), executorService, wrapper);
	}

	private static Connection connectToNode() throws IOException {
		String port = System.getProperty("server.port");

		if (port != null) {
			Socket socket = new Socket("localhost", Integer.parseInt(port));

			InputStream in = socket.getInputStream();
			OutputStream out = socket.getOutputStream();

			Log.info("Connected to parent using socket on port " + port);
			return new Connection(in, out, socket);
		}
		else {
			InputStream in = System.in;
			PrintStream out = System.out;

			Log.info("Connected to parent using stdio");

			return new Connection(in, out, null);
		}
	}

	protected Future<Void> runAsync(Connection connection) throws Exception {
		LanguageServer server = createServer();
		ExecutorService executor = createServerThreads();
		Function<MessageConsumer, MessageConsumer> wrapper = (MessageConsumer consumer) -> {
			return (msg) -> {
				try {
					consumer.consume(msg);
				} catch (UnsupportedOperationException e) {
					//log a warning and ignore. We are getting some messages from vsCode the server doesn't know about
					log.warn("Unsupported message was ignored!", e);
				}
			};
		};
		Launcher<STS4LanguageClient> launcher = Launcher.createLauncher(server,
				STS4LanguageClient.class,
				connection.in,
				connection.out,
				executor,
				wrapper
		);

		if (server instanceof LanguageClientAware) {
			LanguageClient client = launcher.getRemoteProxy();
			((LanguageClientAware) server).connect(client);
		}

		return launcher.startListening();
	}

	final SimpleLanguageServer createServer() {
		return languageServerFactory.get();
	}

}
