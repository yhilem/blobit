/*
 Licensed to Diennea S.r.l. under one
 or more contributor license agreements. See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership. Diennea S.r.l. licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.

 */
package org.blobit.core.cluster;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.bookkeeper.client.api.BKException;
import org.apache.bookkeeper.client.api.BookKeeper;
import org.apache.bookkeeper.client.api.LedgerEntry;

import org.blobit.core.api.ObjectManagerException;

import io.netty.buffer.ByteBuf;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.bookkeeper.client.api.DigestType;
import org.apache.bookkeeper.client.api.ReadHandle;
import org.blobit.core.api.ObjectManagerRuntimeException;

/**
 * Writes all data for a given bucket
 *
 * @author enrico.olivelli
 */
public class BucketReader {

    private static final Logger LOG = Logger.getLogger(BucketReader.class.getName());

    private final ReadHandle lh;
    private final boolean owningHandle;
    private volatile boolean valid;
    private AtomicInteger pendingReads = new AtomicInteger();
    private final BookKeeperBlobManager blobManager;
    private static final byte[] DUMMY_PWD = new byte[0];

    public BucketReader(ReadHandle lh,
            BookKeeperBlobManager blobManager) throws ObjectManagerException {
        this.lh = lh;
        this.blobManager = blobManager;
        this.valid = true;
        this.owningHandle = false;
    }

    public BucketReader(long ledgerId, BookKeeper bookKeeper,
            BookKeeperBlobManager blobManager) throws ObjectManagerException {

        LOG.log(Level.FINE, "Opening BucketReader for ledger {0}", ledgerId);

        try {
            this.owningHandle = true;
            this.blobManager = blobManager;
            this.lh = bookKeeper.newOpenLedgerOp()
                    .withPassword(DUMMY_PWD)
                    .withDigestType(DigestType.CRC32C)
                    .withLedgerId(ledgerId)
                    .withRecovery(false)
                    .execute()
                    .get();
            valid = true;
        } catch (InterruptedException | ExecutionException ex) {
            throw new ObjectManagerException(ex);
        }

        LOG.log(Level.INFO, "Opened BucketReader for ledger {0}", ledgerId);
    }

    public CompletableFuture<byte[]> readObject(long entryId, long last, int length) {

        pendingReads.incrementAndGet();
        return lh.readUnconfirmedAsync(entryId, last)
                .handle((Iterable<LedgerEntry> entries, Throwable u) -> {
                    pendingReads.decrementAndGet();
                    if (u != null) {
                        valid = false;
                        throw new ObjectManagerRuntimeException(new ObjectManagerException(u));
                    }

                    final byte[] data = new byte[length];
                    int offset = 0;

                    for (LedgerEntry entry : entries) {
                        ByteBuf buf = entry.getEntryBuffer();
                        int readable = buf.readableBytes();
                        buf.readBytes(data, offset, readable);
                        offset += readable;
                        entry.close();
                    }

                    return data;
                });

    }

    public CompletableFuture<?> streamObject(long firstEntryId, long last,
            long length, int entrySize, long objectLength, OutputStream output, long offset) {
        LOG.info("streamObject ledgerId: " + firstEntryId + ", lastEntryId " + last + ", stream len " + length + ", entrySize " + entrySize + ", offset=" + offset);
        pendingReads.incrementAndGet();

        // skip first chunks
        while (offset >= entrySize) {
            firstEntryId++;
            offset -= entrySize;
        }

        long scheduledLength = 0;
        long remainingLength = length;
        if (remainingLength > objectLength) {
            remainingLength = objectLength;
        }

        // offset now is valid only for the first entry
        int _remainingOffsetFirstEntry = (int) offset;
        CompletableFuture<?> currentStage = null;
        long currentEntryId = firstEntryId;

        AtomicLong totalWrittenToStream = new AtomicLong();
        boolean firstEntry = true;
        while (scheduledLength < length) {
            long sizeUpToEntry = (currentEntryId - firstEntryId) * entrySize;
            int currentEntrySize = currentEntryId == last ? (int) (objectLength - sizeUpToEntry) : entrySize;
            final long _currentEntryId = currentEntryId;
            
            long readFromThisEntry = firstEntry ? (currentEntrySize - _remainingOffsetFirstEntry) : currentEntrySize;

            final int _bytesToDownload = (int) readFromThisEntry;

            LOG.info("scheduledLength:" + scheduledLength + "/"+length+", _currentEntryId " + _currentEntryId + ", currentEntrySize:" + currentEntrySize + ", _bytesToDownload:" + _bytesToDownload + " __remainingOffsetFirstEntry " + _remainingOffsetFirstEntry + "  remainingLength " + remainingLength+", readFromThisEntry:" + readFromThisEntry);
            firstEntry = false;
            remainingLength -= readFromThisEntry;

            scheduledLength += readFromThisEntry;
            LOG.info("newscheduledLength:" + scheduledLength);
            if (currentStage == null) {
                // first one, a little special
                currentStage = lh.readUnconfirmedAsync(_currentEntryId, _currentEntryId)
                        .handle((Iterable<LedgerEntry> entries, Throwable u) -> {
                            if (u != null) {
                                valid = false;
                                throw new ObjectManagerRuntimeException(new ObjectManagerException(u));
                            }

                            for (LedgerEntry entry : entries) {
                                ByteBuf buf = entry.getEntryBuffer();
                                int readable = buf.readableBytes();
                                final int toRead = Math.min(_bytesToDownload, readable - _remainingOffsetFirstEntry);
                                LOG.info("received data for first entry, id " + _currentEntryId + ", readable = " + readable + ", _remainingOffsetFirstEntry:" + _remainingOffsetFirstEntry + ", toread " + toRead + ", _bytesToDownload:" + _bytesToDownload);
                                byte[] data = new byte[toRead];
                                buf.skipBytes(_remainingOffsetFirstEntry);
                                buf.readBytes(data, 0, toRead);
                                entry.close();

                                // write to client                        
                                try {
                                    output.write(data, 0, toRead);
                                    totalWrittenToStream.addAndGet(toRead);
                                } catch (IOException err) {
                                    throw new ObjectManagerRuntimeException(new ObjectManagerException(err));
                                }
                                LOG.info("received data for first entry, id " + _currentEntryId + ", written !, total " + _bytesToDownload);
                            }
                            return null;
                        });
            } else {
                CompletableFuture nextStage = new CompletableFuture();
                currentStage.handle((value, error) -> {
                    if (error != null) {
                        LOG.log(Level.INFO, "prev stage completed with error", error);
                        nextStage.completeExceptionally(error);
                    } else {
                        LOG.info("prev stage completed, now sending readUnconfirmedAsync for " + _currentEntryId);
                        lh.readUnconfirmedAsync(_currentEntryId, _currentEntryId)
                                .handle((Iterable<LedgerEntry> entries, Throwable u) -> {
                                    if (u != null) {
                                        valid = false;
                                        throw new ObjectManagerRuntimeException(new ObjectManagerException(u));
                                    }
                                    for (LedgerEntry entry : entries) {
                                        ByteBuf buf = entry.getEntryBuffer();
                                        int readable = buf.readableBytes();
                                        final int toRead = Math.min(_bytesToDownload, readable);
                                        LOG.info("received data for non-first entry, id " + _currentEntryId + ", readable = " + readable + ", _remainingOffsetFirstEntry:" + _remainingOffsetFirstEntry + ", toread " + toRead + ", _bytesToDownload:" + _bytesToDownload);
                                        byte[] data = new byte[toRead];
                                        buf.readBytes(data, 0, toRead);
                                        entry.close();

                                        // write to client                        
                                        try {
                                            output.write(data, 0, toRead);
                                            totalWrittenToStream.addAndGet(toRead);
                                        } catch (IOException err) {
                                            throw new ObjectManagerRuntimeException(new ObjectManagerException(err));
                                        }
                                        LOG.info("received data for non-first entry, id " + _currentEntryId + ", written !, total " + _bytesToDownload);
                                    }
                                    nextStage.complete(null);
                                    return null;
                                });
                    }
                    return null;
                });
                currentStage = nextStage;
            }
            currentEntryId++;

        }

        return currentStage.handle((a, b) -> {
            LOG.info("completed read, totalWrittenToStream: " + totalWrittenToStream);
            pendingReads.decrementAndGet();
            return null;
        });
    }

    public boolean isValid() {
        return valid;

    }

    public void close() {
        LOG.log(Level.SEVERE, "closing {0}", this);
        blobManager.scheduleReaderDisposal(this);

    }

    void releaseResources() {
        if (pendingReads.get() > 0) {
            blobManager.scheduleReaderDisposal(this);

        } else {
            if (owningHandle) {
                try {
                    lh.close();
                } catch (BKException | InterruptedException err) {
                    LOG.log(Level.SEVERE, "error while closing ledger " + lh.getId(), err);
                }
            }
        }
    }

    @Override
    public String toString() {
        return "BucketReader{" + "own=" + owningHandle + ", lId=" + lh.getId() + '}';
    }

}
