package fr.uge.ugegreed;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Optional;
import java.util.Scanner;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

import static fr.uge.clienttest.Client.checkerFromDisk;

public class Application {

	/* Taille max d'un buffer */
	private static final int BUFFER_SIZE = 1_024;

	/* Logger pour print proprement */
	private static final Logger logger = Logger.getLogger(Application.class.getName());

	/* Chemin pour les résultats */
	private final Path pathResults;

	/* Application se comportant comme un serveur (PARENT) */
	private final ServerSocketChannel serverSocketChannel;

	/* Application se comportant comme un client (FILS) */
	private SocketChannel sc;
	private InetSocketAddress serverAddress;
	private Context uniqueContext; // PARENT

	/* Selecteur */
	private final Selector selector;

	/* Enumeration des deux commandes START et DISCONNECT */
	private enum Command {
		START, DISCONNECT
	};

	/* Lignes des commandes */
	private final ArrayBlockingQueue<String[]> lineCommandsQueue = new ArrayBlockingQueue<String[]>(10);

	/* Commandes */
	private final ArrayBlockingQueue<Command> commandsQueue = new ArrayBlockingQueue<Command>(10);

	/* id conjecture */
	private int id = 0;

	/* map contenant les fichiers de résultats pour chaque id de conjecture */
	private final HashMap<Integer, String> idFilename = new HashMap<>();

	public Application(int port, String path, InetSocketAddress serverAddress) throws IOException {
		pathResults = Path.of(path);
		serverSocketChannel = ServerSocketChannel.open();
		serverSocketChannel.bind(new InetSocketAddress(port));
		selector = Selector.open();
		this.serverAddress = serverAddress;
		this.sc = SocketChannel.open();
	}

	public Application(int port, String path) throws IOException {
		this(port, path, null);
	}

	/* Lancement et Traitement des commandes */

	public void launch() throws IOException, NumberFormatException, InterruptedException {
		serverSocketChannel.configureBlocking(false);
		serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);

		Thread console = new Thread(this::consoleRun);
		console.setDaemon(true);
		console.start();

		if (serverAddress != null) {
			sc.configureBlocking(false);
			var key = sc.register(selector, SelectionKey.OP_CONNECT);
			uniqueContext = new Context(this, key);
			key.attach(uniqueContext);
			sc.connect(serverAddress);
		}

		while (!Thread.interrupted()) {
			try {
				// System.out.println("Starting select");
				selector.select(this::treatKey);
				processCommands();
			} catch (UncheckedIOException tunneled) {
				throw tunneled.getCause();
			}
			// System.out.println("Select finished");
		}
	}

	private void consoleRun() {
		try (var scanner = new Scanner(System.in)) {
			while (scanner.hasNextLine()) {
				var line = scanner.nextLine();
				switch (line) {
					case "DISCONNECT":
						sendCommand(Command.DISCONNECT);
						break;
					default:
						var lineCmd = line.split(" ");
						if (lineCmd.length != 6 || !lineCmd[0].equals("START")) {
							System.out.println("Invalid command " + line);
						} else {
							sendCommand(lineCmd, Command.START);
						}
				}
			}
		} catch (InterruptedException e) {
			logger.info("Console thread has been interrupted !");
		} finally {
			logger.info("Console thread stopping !");
		}
	}

	private void sendCommand(Command cmd) throws InterruptedException {
		synchronized (commandsQueue) {
			commandsQueue.put(cmd);
			selector.wakeup();
		}
	}

	private void sendCommand(String[] lineCmd, Command cmd) throws InterruptedException {
		synchronized (commandsQueue) {
			lineCommandsQueue.put(lineCmd);
			commandsQueue.put(cmd);
			selector.wakeup();
		}
	}

	private void processCommands() throws IOException, NumberFormatException, InterruptedException {
		Command line;
		while ((line = commandsQueue.poll()) != null) {
			synchronized (commandsQueue) {
				switch (line) {
					case START:
						var lineCmd = lineCommandsQueue.poll();
						id += 1;
						idFilename.put(id, lineCmd[5]);
						try (var writer = Files.newBufferedWriter(Path.of(pathResults + "/" + idFilename.get(id)),
								StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
							writer.write("Résultats conjecture : " + lineCmd[2]);
							writer.newLine();
							writer.newLine();
							writer.write("Range : " + Integer.parseInt(lineCmd[3]) + " -> " + Integer.parseInt(lineCmd[4]));
							writer.newLine();
							writer.newLine();
						} catch (IOException e) {
							System.err.println(e.getMessage());
							System.exit(1);
							return;
						}
						broadcast(id, lineCmd[1], lineCmd[2], Integer.parseInt(lineCmd[3]), Integer.parseInt(lineCmd[4]));
						break;
					case DISCONNECT:
						disconnect();
				}
			}
		}
	}

	/* SERVEUR */

	private void doAccept(SelectionKey key) throws IOException {
		ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
		SocketChannel sc = ssc.accept();
		if (sc == null) {
			// toujours vérifier si la tentative à échoué !
			// auquel cas il faut attendre d'être à nouveau notifié
			return;
		}
		sc.configureBlocking(false);
		var clientKey = sc.register(selector, SelectionKey.OP_READ);
		clientKey.attach(new Context(this, clientKey));
		logger.info("Connexion avec un nouveau client établie !");
	}

	/* SERVEUR et CLIENT */

	private void broadcast(int id, String urlJar, String fullyQualifiedName, int startRange, int endRange)
			throws InterruptedException {
		int loop = 0;
		int last = 0;
		for (var connected : selector.keys()) {
			if (connected.channel() == serverSocketChannel) {
				if ((startRange + ((loop + 1) * ((endRange - startRange) / selector.keys().size()))) == endRange) {
					doConjecture(id, (startRange + (loop * ((endRange - startRange) / selector.keys().size()))),
							(startRange + ((loop + 1) * ((endRange - startRange) / selector.keys().size()))), urlJar,
							fullyQualifiedName);
				} else {
					doConjecture(id, (startRange + (loop * ((endRange - startRange) / selector.keys().size()))),
							(startRange + ((loop + 1) * ((endRange - startRange) / selector.keys().size()))) - 1,
							urlJar, fullyQualifiedName);
				}

			} else if (connected.attachment() != null) {
				if ((startRange + ((loop + 1) * ((endRange - startRange) / selector.keys().size()))) == endRange) {
					((Context) connected.attachment()).queueConjecture(serverSocketChannel.socket().getLocalPort(),
							serverSocketChannel.socket().getLocalPort(), id,
							(startRange + (loop * ((endRange - startRange) / selector.keys().size()))),
							(startRange + ((loop + 1) * ((endRange - startRange) / selector.keys().size()))), urlJar,
							fullyQualifiedName, idFilename.get(id));
					last = (startRange + ((loop + 1) * ((endRange - startRange) / selector.keys().size())));
				} else {
					((Context) connected.attachment()).queueConjecture(serverSocketChannel.socket().getLocalPort(),
							serverSocketChannel.socket().getLocalPort(), id,
							(startRange + (loop * ((endRange - startRange) / selector.keys().size()))),
							(startRange + ((loop + 1) * ((endRange - startRange) / selector.keys().size()))) - 1,
							urlJar, fullyQualifiedName, idFilename.get(id));
					last = (startRange + ((loop + 1) * ((endRange - startRange) / selector.keys().size()))) - 1;
				}
			}
			loop += 1;
		}
		if (last < endRange) {
			doConjecture(id, last + 1, endRange, urlJar, fullyQualifiedName);
		}
	}

	private void broadcast(int src, int dst, int id, String urlJar, String fullyQualifiedName, int startRange,
						   int endRange, String filename) throws InterruptedException {
		int loop = 0;
		int last = 0;
		for (var connected : selector.keys()) {
			if (connected.attachment() != null) {
				if ((startRange + ((loop + 1) * ((endRange - startRange) / selector.keys().size()))) == endRange) {
					((Context) connected.attachment()).queueConjecture(src, dst, id,
							(startRange + (loop * ((endRange - startRange) / selector.keys().size()))),
							(startRange + ((loop + 1) * ((endRange - startRange) / selector.keys().size()))), urlJar,
							fullyQualifiedName, filename);
					last = (startRange + ((loop + 1) * ((endRange - startRange) / selector.keys().size())));
				} else {
					((Context) connected.attachment()).queueConjecture(src, dst, id,
							(startRange + (loop * ((endRange - startRange) / selector.keys().size()))),
							(startRange + ((loop + 1) * ((endRange - startRange) / selector.keys().size()))) - 1,
							urlJar, fullyQualifiedName, filename);
					last = (startRange + ((loop + 1) * ((endRange - startRange) / selector.keys().size()))) - 1;
				}
			}
			loop += 1;
		}
		if (last < endRange) {
			doConjecture(filename, last + 1, endRange, urlJar, fullyQualifiedName);
		}
	}

	private void doConjecture(int id, int start, int end, String urlJar, String fullyQualifiedName)
			throws InterruptedException {
		Optional<Checker> checker = checkerFromDisk(Path.of(urlJar), fullyQualifiedName);
		try (var writer = Files.newBufferedWriter(Path.of(pathResults + "/" + idFilename.get(id)),
				StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
			for (var value = start; value <= end; value++) {
				writer.write(checker.get().check(value));
				writer.newLine();
			}
		} catch (IOException e) {
			System.err.println(e.getMessage());
			System.exit(1);
			return;
		}
	}

	private void doConjecture(String filename, int start, int end, String urlJar, String fullyQualifiedName)
			throws InterruptedException {
		Optional<Checker> checker = checkerFromDisk(Path.of(urlJar), fullyQualifiedName);
		try (var writer = Files.newBufferedWriter(Path.of(pathResults + "/" + filename), StandardOpenOption.CREATE,
				StandardOpenOption.APPEND)) {
			for (var value = start; value <= end; value++) {
				writer.write(checker.get().check(value));
				writer.newLine();
			}
		} catch (IOException e) {
			System.err.println(e.getMessage());
			System.exit(1);
			return;
		}
	}

	static private class Context {
		private final Application application;
		private final SelectionKey key;
		private final SocketChannel sc;
		private final ByteBuffer bufferIn = ByteBuffer.allocate(BUFFER_SIZE);
		private final ByteBuffer bufferOut = ByteBuffer.allocate(BUFFER_SIZE);

		private final ArrayDeque<FrameConjecture> queueConjecture = new ArrayDeque<>();
		private final ArrayDeque<FrameAddress> queueDisconnect = new ArrayDeque<>();

		private final FrameConjectureReader frameConjectureReader = new FrameConjectureReader();
		private final FrameAddressReader frameAddressReader = new FrameAddressReader();

		private boolean closed = false;

		private Context(Application application, SelectionKey key) {
			this.application = application;
			this.key = key;
			this.sc = (SocketChannel) key.channel();
		}

		public void doConnect() throws IOException {
			if (!sc.finishConnect())
				return; // the selector gave a bad hint
			key.interestOps(SelectionKey.OP_READ);
			logger.info("Connexion au serveur établie !");
		}

		public void queueConjecture(int src, int dst, int id, int start, int end, String urlJar,
									String fullyQualifiedName, String filename) {
			queueConjecture.add(new FrameConjecture(src, dst, id, start, end, urlJar, fullyQualifiedName, filename));
			processOut();
		}

		public void queueDisconnect(int port) throws IOException {
			queueDisconnect.add(new FrameAddress(port));
			processOut();
			doWrite();
		}

		private void updateInterestOps() {
			int interestOps = 0;
			if (!closed && bufferIn.hasRemaining()) {
				interestOps |= SelectionKey.OP_READ;
			}
			if (bufferOut.position() > 0) {
				interestOps |= SelectionKey.OP_WRITE;
			}
			key.interestOps(interestOps);
		}

		private void processIn() throws IOException {
			if (bufferIn.position() >= Integer.BYTES) {
				int id = bufferIn.flip().getInt();
				bufferIn.compact();
				if (id == 0) {
					for (;;) {
						Reader.ProcessStatus status = frameConjectureReader.process(bufferIn);
						switch (status) {
							case DONE:
								FrameConjecture frameConjecture = frameConjectureReader.get();
								if (frameConjecture.end() - frameConjecture.start() > 25) {
									try {
										application.broadcast(frameConjecture.src(), frameConjecture.dst(), frameConjecture.id(), frameConjecture.urlJar(),
												frameConjecture.fullyQualifiedName(), frameConjecture.start() + 25,
												frameConjecture.end(), frameConjecture.filename());
									} catch (InterruptedException e) {
										logger.info("Une erreur est survenue");
										silentlyClose();
									}
									try {
										application.doConjecture(frameConjecture.filename(), frameConjecture.start(),
												frameConjecture.start() + 24, frameConjecture.urlJar(),
												frameConjecture.fullyQualifiedName());
									} catch (InterruptedException e) {
										logger.info("Une erreur est survenue");
										silentlyClose();
									}
								} else {
									try {
										application.doConjecture(frameConjecture.filename(), frameConjecture.start(),
												frameConjecture.end(), frameConjecture.urlJar(),
												frameConjecture.fullyQualifiedName());
									} catch (InterruptedException e) {
										logger.info("Une erreur est survenue");
										silentlyClose();
									}
								}
								frameConjectureReader.reset();
								processOut();
								break;
							case REFILL:
								return;
							case ERROR:
								silentlyClose();
								return;
						}
					}
				} else if (id == 1) {
					// RESULTATS A TRAITER
				} else if (id == 2) {
					for (;;) {
						Reader.ProcessStatus status = frameAddressReader.process(bufferIn);
						switch (status) {
							case DONE:
								FrameAddress frameAddress = frameAddressReader.get();
								application.connect(frameAddress.port());
								frameAddressReader.reset();
								silentlyClose();
								break;
							case REFILL:
								return;
							case ERROR:
								silentlyClose();
								return;
						}
					}
				}
			}
		}

		private void processOut() {
			if (queueDisconnect.size() > 0) {
				while (queueDisconnect.size() > 0) {
					if (bufferOut.remaining() >= Integer.BYTES + Integer.BYTES) {
						bufferOut.putInt(2);
						FrameAddress address = queueDisconnect.poll();
						bufferOut.putInt(address.port());
					}
				}
			} else {
				while (queueConjecture.size() > 0) {
					if (bufferOut.remaining() >= Integer.BYTES * 9 + queueConjecture.peek().urlJar().length()
							+ queueConjecture.peek().fullyQualifiedName().length()
							+ queueConjecture.peek().filename().length()) {
						var frameConjecture = queueConjecture.poll();
						bufferOut.putInt(0);
						bufferOut.putInt(frameConjecture.src());
						bufferOut.putInt(frameConjecture.dst());
						bufferOut.putInt(frameConjecture.id());
						bufferOut.putInt(frameConjecture.start());
						bufferOut.putInt(frameConjecture.end());
						bufferOut.putInt(frameConjecture.urlJar().length());
						bufferOut.put(StandardCharsets.UTF_8.encode(frameConjecture.urlJar()));
						bufferOut.putInt(frameConjecture.fullyQualifiedName().length());
						bufferOut.put(StandardCharsets.UTF_8.encode(frameConjecture.fullyQualifiedName()));
						bufferOut.putInt(frameConjecture.filename().length());
						bufferOut.put(StandardCharsets.UTF_8.encode(frameConjecture.filename()));
						continue;
					}
				}
			}
			updateInterestOps();
		}

		public void doRead() throws IOException {
			int bytesRead = sc.read(bufferIn);
			if (bytesRead == -1) {
				closed = true;
				silentlyClose();
				return;
			}
			processIn();
		}

		public void doWrite() throws IOException {
			sc.write(bufferOut.flip());
			bufferOut.compact();
			updateInterestOps();
		}

		private void silentlyClose() {
			Channel sc = (Channel) key.channel();
			try {
				sc.close();
			} catch (IOException e) {
				// ignore exception
			}
		}
	}

	private void treatKey(SelectionKey key) {
		try {
			if (key.isValid() && key.isAcceptable()) {
				doAccept(key);
			}
		} catch (IOException ioe) {
			// lambda call in select requires to tunnel IOException
			throw new UncheckedIOException(ioe);
		}
		try {
			if (key.isValid() && key.isConnectable()) {
				uniqueContext.doConnect();
			}
		} catch (IOException ioe) {
			silentlyClose(key);
			// lambda call in select requires to tunnel IOException
			throw new UncheckedIOException(ioe);
		}
		try {
			if (key.isValid() && key.isWritable()) {
				((Context) key.attachment()).doWrite();
			}
			if (key.isValid() && key.isReadable()) {
				((Context) key.attachment()).doRead();
			}
		} catch (IOException e) {
			logger.log(Level.INFO, "Connection closed with client due to IOException", e);
			silentlyClose(key);
		}
	}

	private void connect(int port) throws IOException {
		sc.close();
		sc = SocketChannel.open();
		sc.configureBlocking(false);
		serverAddress = new InetSocketAddress("localhost", port);
		var key = sc.register(selector, SelectionKey.OP_CONNECT);
		uniqueContext = new Context(this, key);
		key.attach(uniqueContext);
		sc.connect(serverAddress);
	}

	private void disconnect() throws IOException {
		if (serverAddress != null) {
			for (SelectionKey key : selector.keys()) {
				if (!key.isValid() || key.attachment() == null || key.equals(uniqueContext.key)) {
					continue;
				}
				Context ctx = (Context) key.attachment();
				ctx.queueDisconnect(serverAddress.getPort());
				ctx.silentlyClose();
			}
			serverSocketChannel.close();
			sc.close();
			selector.close();
		}
	}

	private void silentlyClose(SelectionKey key) {
		Channel sc = (Channel) key.channel();
		try {
			sc.close();
		} catch (IOException e) {
			// ignore exception
		}
	}

	public static void main(String[] args) throws NumberFormatException, IOException, InterruptedException {
		if (args.length == 2) {
			/* The application is ROOT */
			new Application(Integer.parseInt(args[0]), args[1]).launch();
		} else if (args.length == 4) {
			/* The application is no ROOT */
			new Application(Integer.parseInt(args[0]), args[1],
					new InetSocketAddress(args[2], Integer.parseInt(args[3]))).launch();
		}
	}
}