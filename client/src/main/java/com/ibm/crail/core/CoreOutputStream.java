/*
 * Crail: A Multi-tiered Distributed Direct Access File System
 *
 * Author: Patrick Stuedi <stu@zurich.ibm.com>
 *
 * Copyright (C) 2016, IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.ibm.crail.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;

import com.ibm.crail.CrailOutputStream;
import com.ibm.crail.CrailResult;
import com.ibm.crail.conf.CrailConstants;
import com.ibm.crail.datanode.DataNodeEndpoint;
import com.ibm.crail.datanode.DataResult;
import com.ibm.crail.namenode.protocol.BlockInfo;
import com.ibm.crail.utils.CrailImmediateOperation;
import com.ibm.crail.utils.CrailUtils;

import sun.nio.ch.DirectBuffer;

public class CoreOutputStream extends CoreStream implements CrailOutputStream {
	private static final Logger LOG = CrailUtils.getLogger();
	private AtomicLong inFlight;
	private long writeHint;
	private CrailImmediateOperation noOp;
	
	public CoreOutputStream(CoreNode file, long streamId, long writeHint) throws Exception {
		super(file, streamId, file.getCapacity());
		this.writeHint = Math.max(0, writeHint);
		this.inFlight = new AtomicLong(0);
		this.noOp = new CrailImmediateOperation(0);
		if (CrailConstants.DEBUG){
			LOG.info("CoreOutputStream, open, path " + file.getPath() + ", fd " + file.getFd() + ", streamId " + streamId + ", isDir " + file.isDir() + ", writeHint " + this.writeHint);
		}
	}
	
	final public Future<CrailResult> write(ByteBuffer dataBuf) throws Exception {
		if (!isOpen()) {
			throw new IOException("Stream closed, cannot write");
		}
		if (!(dataBuf instanceof DirectBuffer)) {
			throw new IOException("buffer not offheap");
		}
		if (dataBuf.remaining() <= 0) {
			return noOp;
		}
		
		inFlight.incrementAndGet();
		Future<CrailResult> future = dataOperation(dataBuf);
		long nextOffset = CrailUtils.nextBlockAddress(position());
		if (nextOffset < writeHint){
			prefetchMetadata();
		} 
		return future;
	}	
	
	final public long getWriteHint() {
		return this.writeHint;
	}
	
	public Future<Void> sync() throws IOException {
		if (inFlight.get() != 0){
			LOG.info("Cannot sync, pending operations, opcount " + inFlight.get());
			throw new IOException("Cannot close, pending operations, opcount " + inFlight.get());
		}		
		return super.sync();
	}
	
	public void close() throws IOException {
		if (!isOpen()){
			return;
		}		
		
		if (inFlight.get() != 0){
			LOG.info("Cannot close, pending operations, opcount " + inFlight.get());
			throw new IOException("Cannot close, pending operations, opcount " + inFlight.get() + ", fd " + getFile().getFd() + ", streamId " + getStreamId() + ", capacity " + getFile().getCapacity());
		}		
		super.close();
		if (CrailConstants.DEBUG){
			LOG.info("CoreOutputStream, close, path " + this.getFile().getPath() + ", fd " + getFile().getFd() + ", streamId " + getStreamId() + ", capacity " + getFile().getCapacity());
		}	
	}
	
	// ----------------------
	
	public Future<DataResult> trigger(DataNodeEndpoint endpoint, CoreSubOperation opDesc, ByteBuffer buffer, ByteBuffer region, BlockInfo block) throws Exception {
		Future<DataResult> dataFuture = endpoint.write(buffer, region, block, opDesc.getBlockOffset());
		return dataFuture;		
	}	
	
	public synchronized void update(long newCapacity) {
		inFlight.decrementAndGet();
		setCapacity(newCapacity);
	}
	
	public long getInFlight(){
		return inFlight.get();
	}
}
