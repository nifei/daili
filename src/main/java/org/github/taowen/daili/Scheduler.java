package org.github.taowen.daili;

import kilim.Pausable;
import kilim.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.TimeoutException;

public class Scheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);
    protected Selector selector;
    private Queue<Task> readyTasks = new LinkedList<Task>();
    private Queue<SelectorBooking> selectorBookings = new PriorityQueue<SelectorBooking>();
    public int timeout = 1000 * 60;

    {
        try {
            selector = Selector.open();
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("selector opened");
            }
        } catch (IOException e) {
            LOGGER.error("failed to open selector", e);
        }
    }

    public SocketChannel accept(ServerSocketChannel serverSocketChannel) throws IOException, Pausable, TimeoutException {
        SocketChannel socketChannel = serverSocketChannel.accept();
        if (null != socketChannel) {
            return socketChannel;
        }
        SelectionKey selectionKey = serverSocketChannel.keyFor(selector);
        if (null == selectionKey) {
            selectionKey = serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
            SelectorBooking booking = addSelectorBooking(selectionKey);
            selectionKey.attach(booking);
        } else {
            selectionKey.interestOps(selectionKey.interestOps() | SelectionKey.OP_ACCEPT);
        }
        SelectorBooking booking = (SelectorBooking) selectionKey.attachment();
        booking.acceptBlocked(getCurrentTimeMillis() + timeout);
        return serverSocketChannel.accept();
    }

    private SelectorBooking addSelectorBooking(SelectionKey selectionKey) {
        return new SelectorBooking(selectorBookings, selectionKey);
    }

    public void callSoon(Task task) {
        if (!readyTasks.offer(task)) {
            throw new RuntimeException("failed to add ready task");
        }
    }

    public void loop() {
        while (loopOnce()) {
            if (!hasPendingTask()) {
                LOGGER.error("no more task to loop for, quit now...");
                return;
            }
        }
    }

    private boolean hasPendingTask() {
        if (!readyTasks.isEmpty()) {
            return true;
        }
        SelectorBooking booking = selectorBookings.peek();
        if (booking != null && booking.hasPendingTask()) {
            return true;
        }
        return false;
    }

    boolean loopOnce() {
        try {
            executeReadyTasks();
            doSelect();
            Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
            ioUnblocked(iterator);
            while (hasDeadSelectorBooking()) {
                SelectorBooking booking = selectorBookings.poll();
                booking.cancelDeadTasks(getCurrentTimeMillis());
            }
            return true;
        } catch (Exception e) {
            LOGGER.error("loop died", e);
            return false;
        }
    }

    private boolean hasDeadSelectorBooking() {
        SelectorBooking booking = selectorBookings.peek();
        if (null == booking) {
            return false;
        }
        return booking.getEarliestDeadline() < getCurrentTimeMillis();
    }

    protected long getCurrentTimeMillis() {
        return System.currentTimeMillis();
    }

    private void ioUnblocked(Iterator<SelectionKey> iterator) {
        while (iterator.hasNext()) {
            SelectionKey selectionKey = iterator.next();
            iterator.remove();
            SelectorBooking selectorBooking = (SelectorBooking) selectionKey.attachment();
            for (Task task : selectorBooking.ioUnblocked()) {
                callSoon(task);
            }
        }
    }

    protected int doSelect() throws IOException {
        SelectorBooking booking = selectorBookings.peek();
        if (null == booking) {
            return selector.select();
        } else {
            long delta = booking.getEarliestDeadline() - getCurrentTimeMillis();
            if (delta > 0) {
                return selector.select(delta);
            } else {
                return selector.selectNow();
            }
        }
    }

    private void executeReadyTasks() {
        Task task;
        while((task = readyTasks.poll()) != null) {
            executeTask(task);
        }
    }

    private void executeTask(Task task) {
        try {
            task.resume();
        } catch (Exception e) {
            LOGGER.error("failed to execute task: " + task, e);
        }
    }

    public int read(SocketChannel socketChannel, ByteBuffer byteBuffer) throws IOException, Pausable, TimeoutException {
        int bytesCount = socketChannel.read(byteBuffer);
        if (bytesCount > 0) {
            return bytesCount;
        }
        readBlocked(socketChannel);
        return socketChannel.read(byteBuffer);
    }

    private void readBlocked(SelectableChannel channel) throws ClosedChannelException, Pausable, TimeoutException {
        SelectionKey selectionKey = channel.keyFor(selector);
        if (null == selectionKey) {
            selectionKey = channel.register(selector, SelectionKey.OP_READ);
            SelectorBooking booking = addSelectorBooking(selectionKey);
            selectionKey.attach(booking);
        } else {
            selectionKey.interestOps(selectionKey.interestOps() | SelectionKey.OP_READ);
        }
        SelectorBooking booking = (SelectorBooking) selectionKey.attachment();
        booking.readBlocked(getCurrentTimeMillis() + timeout);
    }

    public int write(SocketChannel socketChannel, ByteBuffer byteBuffer) throws IOException, Pausable, TimeoutException {
        int bytesCount = socketChannel.write(byteBuffer);
        if (bytesCount > 0) {
            return bytesCount;
        }
        writeBlocked(socketChannel);
        return socketChannel.write(byteBuffer);
    }

    private void writeBlocked(SelectableChannel channel) throws ClosedChannelException, Pausable, TimeoutException {
        SelectionKey selectionKey = channel.keyFor(selector);
        if (null == selectionKey) {
            selectionKey = channel.register(selector, SelectionKey.OP_WRITE);
            SelectorBooking booking = addSelectorBooking(selectionKey);
            selectionKey.attach(booking);
        } else {
            selectionKey.interestOps(selectionKey.interestOps() | SelectionKey.OP_WRITE);
        }
        SelectorBooking booking = (SelectorBooking) selectionKey.attachment();
        booking.writeBlocked(getCurrentTimeMillis() + timeout);
    }

    public void close() throws IOException {
        for (SelectionKey key : selector.keys()) {
            try {
                key.channel().close();
            } catch (Exception e) {
                LOGGER.error("failed to close channel", e);
            }
        }
        selector.close();
    }

    public void connect(SocketChannel socketChannel, SocketAddress remote) throws IOException, TimeoutException, Pausable {
        if (socketChannel.connect(remote)) {
            return;
        }
        SelectionKey selectionKey = socketChannel.keyFor(selector);
        if (null == selectionKey) {
            selectionKey = socketChannel.register(selector, SelectionKey.OP_CONNECT);
            SelectorBooking booking = addSelectorBooking(selectionKey);
            selectionKey.attach(booking);
        } else {
            selectionKey.interestOps(selectionKey.interestOps() | SelectionKey.OP_CONNECT);
        }
        SelectorBooking booking = (SelectorBooking) selectionKey.attachment();
        booking.connectBlocked(getCurrentTimeMillis() + timeout);
        if (!socketChannel.finishConnect()) {
            throw new RuntimeException("still not connected, after connect op unblocked");
        }
    }

    public SocketAddress receive(DatagramChannel datagramChannel, ByteBuffer byteBuffer) throws IOException, TimeoutException, Pausable {
        SocketAddress clientAddress = datagramChannel.receive(byteBuffer);
        if (null != clientAddress) {
            return clientAddress;
        }
        readBlocked(datagramChannel);
        return datagramChannel.receive(byteBuffer);
    }

    public int send(DatagramChannel channel, ByteBuffer byteBuffer, InetSocketAddress target) throws IOException, TimeoutException, Pausable {
        int bytesCount = channel.send(byteBuffer, target);
        if (bytesCount > 0) {
            return bytesCount;
        }
        writeBlocked(channel);
        return channel.send(byteBuffer, target);
    }
}
