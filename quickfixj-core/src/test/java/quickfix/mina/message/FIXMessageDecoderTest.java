/*******************************************************************************
 * Copyright (c) quickfixengine.org  All rights reserved.
 *
 * This file is part of the QuickFIX FIX Engine
 *
 * This file may be distributed under the terms of the quickfixengine.org
 * license as defined by quickfixengine.org and appearing in the file
 * LICENSE included in the packaging of this file.
 *
 * This file is provided AS IS with NO WARRANTY OF ANY KIND, INCLUDING
 * THE WARRANTY OF DESIGN, MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE.
 *
 * See http://www.quickfixengine.org/LICENSE for licensing information.
 *
 * Contact ask@quickfixengine.org if any conditions of this licensing
 * are not clear to you.
 ******************************************************************************/

package quickfix.mina.message;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;

import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.filter.codec.ProtocolCodecException;
import org.apache.mina.filter.codec.ProtocolDecoder;
import org.apache.mina.filter.codec.demux.DemuxingProtocolCodecFactory;
import org.apache.mina.filter.codec.demux.MessageDecoderResult;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.quickfixj.CharsetSupport;

import quickfix.DataDictionaryTest;
import quickfix.InvalidMessage;
import quickfix.Message;
import quickfix.field.Headline;
import quickfix.mina.CriticalProtocolCodecException;

public class FIXMessageDecoderTest {
    private FIXMessageDecoder decoder;
    private IoBuffer buffer;
    private ProtocolDecoderOutputForTest decoderOutput;

    @Before
    public void setUp() throws Exception {
        decoder = new FIXMessageDecoder();
        buffer = IoBuffer.allocate(8192);
        decoderOutput = new ProtocolDecoderOutputForTest();
    }

    @After
    public void tearDown() throws Exception {
        buffer.clear();
    }

    @Test
    public void testInvalidStringCharset() throws Exception {
        try {
            decoder = new FIXMessageDecoder("BOGUS");
            fail("no exception thrown");
        } catch (UnsupportedEncodingException e) {
            // expected
        }
    }

    @Test
    public void testStringDecoding() throws Exception {
        decoder = new FIXMessageDecoder("UTF-16");
        setUpBuffer("8=FIX.4.2\0019=12\00135=X\001108=30\00110=049\001");
        MessageDecoderResult decoderResult = decoder.decode(null, buffer, decoderOutput);
        assertEquals("wrong decoder result", MessageDecoderResult.OK, decoderResult);
        assertEquals("Wrong encoding", 14397, (int) decoderOutput.getMessage().charAt(0));
    }

    @Test
    public void testFixtStringDecoding() throws Exception {
        decoder = new FIXMessageDecoder("UTF-16");
        setUpBuffer("8=FIXT.1.1\0019=12\00135=X\001108=30\00110=049\001");
        MessageDecoderResult decoderResult = decoder.decode(null, buffer, decoderOutput);
        assertEquals("wrong decoder result", MessageDecoderResult.OK, decoderResult);
        assertEquals("Wrong encoding", 14397, (int) decoderOutput.getMessage().charAt(0));
    }

    @Test
    public void testWesternEuropeanDecoding() throws Exception {
        // Should work with default encoding
        doWesternEuropeanDecodingTest();

        try {
            // Should break
            CharsetSupport.setCharset("US-ASCII");
            doWesternEuropeanDecodingTest();
        } catch (InvalidMessage e) {
            // expected
        } finally {
            CharsetSupport.setCharset(CharsetSupport.getDefaultCharset());
        }
    }

    private void doWesternEuropeanDecodingTest() throws Exception {
        FIXMessageDecoder decoder = new FIXMessageDecoder();
        IoBuffer byteBuffer = IoBuffer.allocate(1024);
        // äbcfödçé
        String headline = "\u00E4bcf\u00F6d\u00E7\u00E9";
        byteBuffer.put(("8=FIX.4.4\0019=18\00135=B\001148=" + headline + "\00110=253\001").getBytes("ISO-8859-1"));
        byteBuffer.flip();

        ProtocolDecoderOutputForTest decoderOutput = new ProtocolDecoderOutputForTest();
        decoder.decode(null, byteBuffer, decoderOutput);

        Message decodedMessage = new Message(decoderOutput.getMessage(), DataDictionaryTest
                .getDictionary(), true);

        assertEquals("wrong text", headline, decodedMessage.getString(Headline.FIELD));
    }

    @Test
    public void testPartialHeader() throws Exception {
        setUpBuffer("8=FIX.4.2");
        assertEquals("wrong result", MessageDecoderResult.NEED_DATA, decoder
                .decodable(null, buffer));
    }

    @Test
    public void testPartialHeader2() throws Exception {
        setUpBuffer("8=FIX.4.2\0019");
        assertEquals("wrong result", MessageDecoderResult.NEED_DATA, decoder
                .decodable(null, buffer));
    }

    @Test
    public void testPartialHeaderFixt() throws Exception {
        setUpBuffer("8=FIXT.1.1\0019");
        assertEquals("wrong result", MessageDecoderResult.NEED_DATA, decoder
                .decodable(null, buffer));
    }

    // QFJ-376
    @Test
    public void testGarbageData() throws Exception {
        setUpBuffer(new byte[8192]);
        MessageDecoderResult result = decoder.decodable(null, buffer);
        assertEquals(result, MessageDecoderResult.NOT_OK);
    }

    @Test
    public void testSimpleMessage() throws Exception {
        String data = setUpBuffer("8=FIX.4.2\0019=12\00135=X\001108=30\00110=049\001");
        assertMessageFound(data);
    }

    @Test
    public void testSplitMessage() throws Exception {
        String data = "8=FIX.4.2\0019=12\00135=X\001108=30\00110=049\001";
        for (int i = 1; i < data.length(); i++) {
            doSplitMessageTest(i, data, 12);
        }
    }

    @Test
    public void testSplitMessageFixt() throws Exception {
        String data = "8=FIXT.1.1\0019=12\00135=X\001108=30\00110=049\001";
        for (int i = 1; i < data.length(); i++) {
            doSplitMessageTest(i, data, 13);
        }
    }

    private void doSplitMessageTest(int splitOffset, String data, int headerSize) throws ProtocolCodecException {
        String firstChunk = data.substring(0, splitOffset);
        String remaining = data.substring(splitOffset);
        buffer.put(firstChunk.getBytes());
        buffer.flip();
        decoderOutput.reset();

        if (splitOffset < headerSize) {
            assertEquals("shouldn't recognize header; offset=" + splitOffset,
                    MessageDecoderResult.NEED_DATA, decoder.decodable(null, buffer));
        } else {

            assertEquals("should recognize header", MessageDecoderResult.OK, decoder.decodable(
                    null, buffer));
            if (splitOffset < data.length()) {
                assertEquals("shouldn't decode message; offset=" + splitOffset,
                        MessageDecoderResult.NEED_DATA, decoder.decode(null, buffer, decoderOutput));
                assertNull("shouldn't write message; offset=" + splitOffset, decoderOutput
                        .getMessage());

                assertEquals("can't change buffer position", 0, buffer.position());
                byte[] bytes = remaining.getBytes();

                buffer.mark();
                buffer.position(buffer.limit());
                buffer.limit(buffer.limit() + bytes.length);
                buffer.put(bytes);
                buffer.reset();

                assertMessageFound(data);
            } else {
                assertEquals("should parse message; offset=" + splitOffset,
                        MessageDecoderResult.OK, decoder.decode(null, buffer, decoderOutput));
                assertNotNull("should write message; offset=" + splitOffset, decoderOutput
                        .getMessage());
            }
        }

        buffer.clear();
    }

    @Test
    public void testGarbageAtStart() throws Exception {
        String data = "8=FIX.4.2\0019=12\00135=X\001108=30\00110=036\001";
        setUpBuffer("8=!@#$%" + data);
        assertMessageFound(data);
    }

    @Test
    public void testBadLengthTooLong() throws Exception {
        String badMessage = "8=FIX.4.2\0019=25\00135=X\001108=30\00110=036\001";
        String goodMessage = "8=FIX.4.2\0019=12\00135=Y\001108=30\00110=037\001";
        setUpBuffer(badMessage + goodMessage + goodMessage);
        assertMessageFound(goodMessage);
    }

    @Test
    public void testMultipleMessagesInBuffer() throws Exception {
        String goodMessage = "8=FIX.4.2\0019=12\00135=X\001108=30\00110=036\001";
        setUpBuffer(goodMessage + goodMessage + goodMessage);
        assertMessageFound(goodMessage, 3);
    }

    /**
     * QFJ-760
     */
    @Test
    public void testMessageWithoutChecksum() throws Exception {
        String badMessage = "8=FIX.4.4\0019=275\00135=D\00134=3\00149=441000-XXXXX-X-XXXX-001\00152=20131113-10:22:31.567\00156=XXXXX\0011=A1\00111=9fef3663330e209e1bce\00118=H\001"
                + "22=4\00138=200\00140=M\00148=XX0005519XXXX\00154=1\00155=[N/A]\00158=MassTest\00159=0\00160=20131113-10:22:31.567\001100=XXXX\001"
                + "526=9fef3663330e209e1bce\00125029=12345\001453=1\001448=XXXXXXXX030\001447=D\001452=3610=016\001";
        setUpBuffer(badMessage);

        assertEquals("wrong decoder result", MessageDecoderResult.NEED_DATA,
                decoder.decode(null, buffer, decoderOutput));
    }

    @Test
    public void testMessageExtraction() throws Exception {
        File testFile = setUpTestFile();

        FIXMessageDecoder decoder = new FIXMessageDecoder();
        List<String> messages = decoder.extractMessages(testFile);
        assertCorrectlyExtractedMessages(messages);
    }

    private void assertCorrectlyExtractedMessages(List<String> messages) {
        assertEquals("wrong # of messages", 4, messages.size());
        for (String message : messages) {
            assertEquals("wrong message", "8=FIX.4.2\0019=12\00135=X\001108=30\00110=036\001",
                    message);
        }
    }

    @Test
    public void testMessageStreamingExtraction() throws Exception {
        File testFile = setUpTestFile();

        FIXMessageDecoder decoder = new FIXMessageDecoder();
        final List<String> messages = new ArrayList<String>();
        decoder.extractMessages(testFile, new FIXMessageDecoder.MessageListener() {
            public void onMessage(String message) {
                messages.add(message);
            }
        });
        assertCorrectlyExtractedMessages(messages);
    }

    private File setUpTestFile() throws IOException {
        String text = "This is a test case for FixMessageDecoder message";
        text += "extraction. There are four messages to extract...\n";
        text += "8=FIX.4.2\0019=12\00135=X\001108=30\00110=036\001\n";
        text += "and 8=FIX.4.2\0019=12\00135=X\001108=30\00110=036\001 (two)\n";
        text += "and 8=FIX.4.2\0019=12\00135=X\001108=30\00110=036\001 (three)\n";
        text += "and 8=FIX.4.2\0019=12\00135=X\001108=30\00110=036\001\n";
        text += "and that's all.\n";

        File f = File.createTempFile("test", ".txt");
        f.deleteOnExit();
        FileOutputStream out = new FileOutputStream(f);
        out.write(text.getBytes());
        out.close();
        return f;
    }

    @Test
    public void testBadLengthTooShort() throws Exception {
        String badMessage = "8=FIX.4.2\0019=10\00135=X\001108=30\00110=036\001";
        String goodMessage = "8=FIX.4.2\0019=12\00135=X\001108=30\00110=036\001";
        setUpBuffer(badMessage + goodMessage);
        assertMessageFound(goodMessage);
    }

    @Test
    public void testBadLengthOnLogon() throws Exception {
        String badMessage = "8=FIX.4.2\0019=10\00135=A\001108=30\00110=036\001";
        setUpBuffer(badMessage);

        try {
            decoder.decode(null, buffer, decoderOutput);
            fail("no exception");
        } catch (CriticalProtocolCodecException e) {
            // expected
        }
    }

    @Test
    public void testBogusMessageLength() throws Exception {
        String badMessage = "8=FIX.4.2\0019=10xyz\00135=X\001108=30\00110=036\001";
        String goodMessage = "8=FIX.4.2\0019=12\00135=X\001108=30\00110=036\001";
        setUpBuffer(badMessage + goodMessage);
        assertMessageFound(goodMessage);
    }

    @Test
    public void testNPE() throws Exception {
        try {
            decoder.decode(null, null, null);
            fail("no exception");
        } catch (ProtocolCodecException e) {
            // expected
            assertTrue(e.getCause() instanceof NullPointerException);
        }
    }

    @Test
    public void testFailedPatternMatchAtEndOfBuffer() throws Exception {
        decoder = new FIXMessageDecoder("UTF-16");
        setUpBuffer("8=FIX.4.2\0019=12\00135=X\001108=30\0011wmyadz");
        MessageDecoderResult decoderResult = decoder.decode(null, buffer, decoderOutput);
        //assertEquals("wrong decoder result", MessageDecoderResult.NOT_OK, decoderResult);
        assertEquals("wrong decoder result", MessageDecoderResult.NEED_DATA, decoderResult);
    }

    @Test
    public void testMinaDemux() throws Exception {
        String message = "8=FIX.4.2\0019=12\00135=X\001108=30\00110=036\001";
        doTestMinaDemux(message);
    }

    @Test
    public void testMinaDemuxFixt() throws Exception {
        String message = "8=FIXT.1.1\0019=12\00135=X\001108=30\00110=036\001";
        doTestMinaDemux(message);
    }

    private void doTestMinaDemux(String message) throws Exception {
        DemuxingProtocolCodecFactory codecFactory = new DemuxingProtocolCodecFactory();
        codecFactory.addMessageDecoder(FIXMessageDecoder.class);

        ProtocolDecoder decoder = codecFactory.getDecoder(null);
        ProtocolDecoderOutputForTest output = new ProtocolDecoderOutputForTest();

        final IoSessionStub mockSession = new IoSessionStub();

        int count = 5;
        String data = "";
        for (int i = 0; i < count; i++) {
            data += message;
        }

        for (int i = 1; i < data.length(); i++) {
            String chunk1 = data.substring(0, i);
            String chunk2 = data.substring(i);
            setUpBuffer(chunk1);

            decoder.decode(mockSession, buffer, output);
            buffer.compact();

            setUpBuffer(chunk2);
            decoder.decode(mockSession, buffer, output);

            assertEquals("wrong message count", count, output.getMessageCount());

            output.reset();
            buffer.clear();
        }
    }

    private void assertMessageFound(String data) throws ProtocolCodecException {
        assertMessageFound(data, 1);
    }

    private void assertMessageFound(String data, int count) throws ProtocolCodecException {
        assertEquals("wrong decoder result", MessageDecoderResult.OK, decoder.decode(null, buffer,
                decoderOutput));
        assertEquals("wrong message count", count, decoderOutput.getMessageCount());
        for (int i = 0; i < count; i++) {
            assertEquals("incorrect msg framing", data, decoderOutput.getMessage(i));
        }
    }

    private String setUpBuffer(String bufferContents) throws UnsupportedEncodingException {
        byte[] bytes = bufferContents.getBytes();
        setUpBuffer(bytes);
        return new String(bytes, "ISO_8859-1");
    }

    private void setUpBuffer(byte[] bytes)
            throws UnsupportedEncodingException {
        buffer.put(bytes);
        buffer.flip();
    }

    // =========================================================================================
    // QFJ-505

    private static final String FIELD_DELIMITER = "\001";
    private static final byte[] HEADER_PATTERN = getBytes("8=FIXt.?.?" + FIELD_DELIMITER + "9=");

    @Test
    public void testCompleteHeader() {
        // 8=FIXT.1.1_9=
        byte[] completeHeader = {0x38, 0x3D, 0x46, 0x49, 0x58, 0x54, 0x2E, 0x31, 0x2E, 0x31, 0x01, 0x39, 0x3D};
        IoBuffer in = IoBuffer.wrap(completeHeader);
        BufPos bufPos = indexOf(in, 0, HEADER_PATTERN);
        Assert.assertTrue("We should have a complete header", bufPos._offset != -1);
    }

    @Test
    public void testLongCompleteHeader() {
        // 8=FIXT.1.1_9======
        byte[] completeHeader = {0x38, 0x3D, 0x46, 0x49, 0x58, 0x54, 0x2E, 0x31, 0x2E, 0x31, 0x01, 0x39, 0x3D, 0x3D, 0x3D, 0x3D, 0x3D, 0x3D};
        IoBuffer in = IoBuffer.wrap(completeHeader);
        BufPos bufPos = indexOf(in, 0, HEADER_PATTERN);
        Assert.assertTrue("We should have a complete header", bufPos._offset != -1);
    }

    @Test
    public void testIncompleteHeader() {
        // 8=FIXT.1.1_9
        byte[] incompleteHeader = {0x38, 0x3D, 0x46, 0x49, 0x58, 0x54, 0x2E, 0x31, 0x2E, 0x31, 0x01, 0x39};
        IoBuffer in = IoBuffer.wrap(incompleteHeader);
        BufPos bufPos = indexOf(in, 0, HEADER_PATTERN);
        Assert.assertTrue("There should be no header detected", bufPos._offset == -1);
    }

    @Test
    public void testCompleteHeader4() {
        // 8=FIX.4.4_9=
        byte[] completeHeader = {0x38, 0x3D, 0x46, 0x49, 0x58, 0x2E, 0x34, 0x2E, 0x34, 0x01, 0x39, 0x3D};
        IoBuffer in = IoBuffer.wrap(completeHeader);
        BufPos bufPos = indexOf(in, 0, HEADER_PATTERN);
        Assert.assertTrue("We should have a complete header", bufPos._offset != -1);
    }

    @Test
    public void testLongCompleteHeader4() {
        // 8=FIX.4.4_9======
        byte[] completeHeader = {0x38, 0x3D, 0x46, 0x49, 0x58, 0x2E, 0x34, 0x2E, 0x34, 0x01, 0x39, 0x3D, 0x3D, 0x3D, 0x3D, 0x3D, 0x3D};
        IoBuffer in = IoBuffer.wrap(completeHeader);
        BufPos bufPos = indexOf(in, 0, HEADER_PATTERN);
        Assert.assertTrue("We should have a complete header", bufPos._offset != -1);
    }

    @Test
    public void testIncompleteHeader4() {
        // 8=FIX.4.4_9
        byte[] incompleteHeader = {0x38, 0x3D, 0x46, 0x49, 0x58, 0x2E, 0x34, 0x2E, 0x34, 0x01, 0x39};
        IoBuffer in = IoBuffer.wrap(incompleteHeader);
        BufPos bufPos = indexOf(in, 0, HEADER_PATTERN);
        Assert.assertTrue("There should be no header detected", bufPos._offset == -1);
    }

    private static byte[] getBytes(String s) {
        try {
            return s.getBytes(CharsetSupport.getDefaultCharset());
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    private static int startsWith(IoBuffer buffer, int bufferOffset, byte[] data) {
        if (bufferOffset + minMaskLength(data) > buffer.limit()) {
            return -1;
        }
        final int initOffset = bufferOffset;
        int dataOffset = 0;
        for (int bufferLimit = buffer.limit(); dataOffset < data.length
                && bufferOffset < bufferLimit; dataOffset++, bufferOffset++) {
            if (buffer.get(bufferOffset) != data[dataOffset] && data[dataOffset] != '?') {
                // Now check for optional characters, at this point we know we didn't
                // match, so we can just check to see if we failed a match on an optional character,
                // and if so then just rewind the buffer one byte and keep going.
                if (Character.toUpperCase(data[dataOffset]) == buffer.get(bufferOffset))
                    continue;
                // Didn't match the optional character, so act like it was not included and keep going
                if (Character.isLetter(data[dataOffset]) && Character.isLowerCase(data[dataOffset])) {
                    --bufferOffset;
                    continue;
                }
                return -1;
            }
        }
        // Make sure every byte from the pattern was parsed
        if (dataOffset < data.length) {
            return -1;
        }
        return bufferOffset - initOffset;
    }

    private static BufPos indexOf(IoBuffer buffer, int position, byte[] data) {
        for (int offset = position, limit = buffer.limit() - minMaskLength(data) + 1; offset < limit; offset++) {
            int length;
            if (buffer.get(offset) == data[0] && (length = startsWith(buffer, offset, data)) > 0) {
                return new BufPos(offset, length);
            }
        }
        return new BufPos(-1, 0);
    }

    private static int minMaskLength(byte[] data) {
        int len = 0;
        for (byte aChar : data) {
            if (Character.isLetter(aChar) && Character.isLowerCase(aChar))
                continue;
            ++len;
        }
        return len;
    }

    static class BufPos {
        final int _offset;
        final int _length;

        /**
         * @param offset
         * @param length
         */
        public BufPos(int offset, int length) {
            _offset = offset;
            _length = length;
        }

        public String toString() {
            return _offset + "," + _length;
        }
    }

}
