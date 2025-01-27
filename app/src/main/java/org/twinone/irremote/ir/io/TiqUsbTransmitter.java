package org.twinone.irremote.ir.io;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Log;
import android.util.Pair;

import org.twinone.irremote.ir.Signal;
import org.twinone.irremote.ui.MainActivity;
import org.twinone.irremote.util.StringUtil;

import java.io.ByteArrayOutputStream;
import java.nio.BufferOverflowException;
import java.util.HashMap;

public class TiqUsbTransmitter extends Transmitter {
    enum TiqCommandId {
        Unknown('H'), Version('V'), Idle('L'),
        Send('S'), Recv('R'), Data('D'),
        Output('O'), Cancel('C');

        private final char value;

        TiqCommandId(char value) {
            this.value = value;
        }
    }

    private static final String TAG = "TiqUsbTransmitter";
    private volatile boolean mWaitingForTransmission;
    private volatile Signal mSignal;
    private volatile boolean mHasTransmittedOnce;
    private UsbDevice mUsbDevice;
    private UsbDeviceConnection mUsbConn;
    private UsbEndpoint mIrEndpointWrite, mIrEndpointRead;
    private byte mCommandId = 1;
    private byte mPacketIndex = 0;
    private final int maxFragmentSize = 56;
    private final Runnable mTransmitRunnable = new TransmitterRunnable();

    private static final int[] freqTable = new int[]{
            38000, 37900, 37917, 36000, 40000, 39700, 35750, 36400, 36700, 37000,
            37700, 38380, 38400, 38462, 38740, 39200, 42000, 43600, 44000, 33000,
            33500, 34000, 34500, 35000, 40500, 41000, 41500, 42500, 43000, 45000};

    private static Pair<Integer, Integer>[] usbDeviceIds = new Pair[]{
            Pair.create(0x10C4, 0x8468),
            Pair.create(0x45E, 0x8468),
    };


    protected TiqUsbTransmitter(Context context) throws Exception {
        super(context);

        tiqiaInit(context);
    }

    @Override
    public void setSignal(Signal signal) {
        mSignal = signal;
    }

    @Override
    protected void transmit() {
        try {
            findFrequencyIdx(mSignal.getFrequency());
            if (mSignal.getFrequency() != 3800) {
                Log.e(TAG, "only frequency 3800 is supported");
            }
            transmitSignal(mSignal);
        } catch (Exception e) {
            Log.e(TAG, "failed to transmit", e);
        }
    }

    @Override
    public void startTransmitting() {
        if (mWaitingForTransmission)
            stopTransmitting(false);

        mWaitingForTransmission = true;
        mHandler.post(mTransmitRunnable);
    }

    @Override
    public void stopTransmitting(boolean transmitAtLeastOnce) {
        if (mHandler == null)
            Log.d(TAG, "Null handler");
        if (mTransmitRunnable == null)
            Log.d(TAG, "Null Runnable");

        mHandler.removeCallbacks(mTransmitRunnable);
        if (transmitAtLeastOnce && !mHasTransmittedOnce) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        transmitSignal(mSignal);
                    } catch (Exception e) {
                        Log.e(TAG, "failed to transmit", e);
                    }
                }
            });
        } else {
            Log.d(TAG, "Not transmitting signal");
            mWaitingForTransmission = false;
        }
        mHasTransmittedOnce = false;
    }

    @Override
    public boolean hasTransmittedOnce() {
        return mHasTransmittedOnce;
    }

    @Override
    public void pause() {
        mHandler.removeCallbacks(mTransmitRunnable);
    }

    private void tiqiaInit(Context context) throws Exception {
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);

        mUsbDevice = null;
        HashMap<String, UsbDevice> devices = manager.getDeviceList();
        for (UsbDevice device : devices.values()) {
            for (Pair<Integer, Integer> deviceId : usbDeviceIds) {
                if (device.getVendorId() == deviceId.first && device.getProductId() == deviceId.second) {
                    mUsbDevice = device;
                    break;
                }
            }
        }

        if (mUsbDevice == null) {
            throw new Exception("no valid USB device found");
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            throw new Exception("lollipop or newer required");
        }

        if (mUsbDevice.getConfigurationCount() != 1) {
            throw new Exception("expecting 1 configuration, found: " + mUsbDevice.getConfigurationCount());
        }

        if (mUsbDevice.getInterfaceCount() != 1) {
            throw new Exception("expecting 1 interface, found: " + mUsbDevice.getInterfaceCount());
        }

        UsbInterface irInterface = mUsbDevice.getInterface(0);

        if (irInterface.getEndpointCount() != 2) {
            throw new Exception("expecting 2 endpoints, found: " + irInterface.getEndpointCount());
        }

        mIrEndpointRead = irInterface.getEndpoint(0);
        if (mIrEndpointRead.getDirection() != UsbConstants.USB_DIR_IN) {
            mIrEndpointWrite = mIrEndpointRead;
            mIrEndpointRead = irInterface.getEndpoint(1);
        } else {
            mIrEndpointWrite = irInterface.getEndpoint(1);
        }

        if (mIrEndpointRead.getDirection() != UsbConstants.USB_DIR_IN ||
                mIrEndpointRead.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK ||
                mIrEndpointWrite.getDirection() != UsbConstants.USB_DIR_OUT ||
                mIrEndpointWrite.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) {
            throw new Exception("invalid endpoints");
        }

        if (!manager.hasPermission(mUsbDevice)) {
            manager.requestPermission(mUsbDevice, MainActivity.permissionIntent);
        } else {
            mUsbConn = manager.openDevice(mUsbDevice);
            mUsbConn.claimInterface(irInterface, true);

            sendCommand(TiqCommandId.Idle);
            sendCommand(TiqCommandId.Send);
        }
    }

    private void transmitSignal(Signal signal) throws Exception {
        if (signal == null) {
            return;
        }

        ByteArrayOutputStream pulseBuf = new ByteArrayOutputStream();


        boolean pulseOn = true;
        for (int pulseDuration : signal.getPattern()) {
            int pulseLength = pulseDuration / 16; // 16us per pulse

            while (pulseLength > 127) {
                if (pulseBuf.size() > 127) {
                    throw new BufferOverflowException();
                }

                pulseBuf.write((byte) (pulseOn ? 0xFF : 0x7F));
                pulseLength -= 127;
            }

            if (pulseBuf.size() > 127) {
                throw new BufferOverflowException();
            }
            pulseBuf.write((byte) (pulseOn ? 0xFF : 0x7F));
            pulseOn = !pulseOn;
        }

        byte[] pulseArr = pulseBuf.toByteArray();


        // TODO: support full table
        // 0 == 38000 (freqTable)
        sendIr(0, pulseArr);
    }

    private int findFrequencyIdx(int frequency) throws Exception {
        if (frequency > freqTable.length) {
            for (int i = 0; i < freqTable.length; i++) {
                if (freqTable[i] == frequency) {
                    return i;
                }
            }

            throw new Exception("invalid frequency: " + frequency);
        }
        return frequency;
    }

    private void sendIr(int frequency, byte[] buf) throws Exception {
        if (buf.length == 0) {
            throw new Exception("can't transmit IR with no data");
        }
        if (buf.length + 7 > 1024) {
            throw new Exception("can't transmit IR, too big");
        }

        ByteArrayOutputStream packetBuf = new ByteArrayOutputStream();

        frequency = findFrequencyIdx(frequency);

        packetBuf.write((byte) 'S');
        packetBuf.write((byte) 'T'); // start signal
        packetBuf.write(getNextCommandId());
        packetBuf.write((byte) TiqCommandId.Data.value);
        packetBuf.write((byte) frequency);

        packetBuf.write(buf);

        packetBuf.write((byte) 'E'); // end signal
        packetBuf.write((byte) 'N');

        byte[] packetArr = packetBuf.toByteArray();
        sendReport(packetArr);

        sendCommand(TiqCommandId.Idle);
    }

    private byte getNextCommandId() {
        if (mCommandId < 0x7F) {
            mCommandId++;
        } else {
            mCommandId = 1;
        }
        return mCommandId;
    }

    private byte getNextPacketIndex() {
        mPacketIndex++;
        if (mPacketIndex > 15) {
            mPacketIndex = 1;
        }
        return mPacketIndex;
    }

    private void sendCommand(TiqCommandId commandType) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        buffer.write(new byte[]{'S', 'T'}); // start sign
        buffer.write((byte) commandType.value);
        buffer.write(getNextCommandId());
        buffer.write(new byte[]{'E', 'N'}); // end sign

        byte[] cmdArr = buffer.toByteArray();

        sendReport(cmdArr);
    }


    private void sendReport(byte[] data) throws Exception {
        if (data.length == 0) {
            throw new Exception("invalid report with no data");
        }
        if (data.length > 1024) {
            throw new Exception("invalid report exceeds USB max packet size");
        }

        // compute the fragment size
        int readPointer = 0;
        int fragmentCount = data.length / maxFragmentSize;
        if (data.length % maxFragmentSize != 0) { // ceiling function
            fragmentCount++;
        }

        byte fragmentIndex = 0;
        while (readPointer < data.length) {
            fragmentIndex++;

            int fragmentSize = data.length - readPointer;
            if (fragmentSize > maxFragmentSize) { // limit to USB fragment size
                fragmentSize = maxFragmentSize;
            }

            ByteArrayOutputStream fragmentBuf =new ByteArrayOutputStream();
            fragmentBuf.write((byte) 2); // Report ID
            fragmentBuf.write((byte) (fragmentSize + 3)); // Fragment Size
            fragmentBuf.write(getNextPacketIndex()); // Packet Index
            fragmentBuf.write((byte) fragmentCount);
            fragmentBuf.write(fragmentIndex);
            fragmentBuf.write(data, readPointer, fragmentSize);

            byte[] outBuf = fragmentBuf.toByteArray();

            Log.w(TAG, "Sending command: " + StringUtil.toHex(outBuf));
            mUsbConn.bulkTransfer(mIrEndpointWrite, outBuf, outBuf.length, 0);

            readPointer += fragmentSize;

        }
        getResponse();
    }

    private void getResponse() {
        byte fragmentCount = 1;
        byte fragmentIndex = 0;

        byte[] fragmentBuf = new byte[63];

        int tries = 5;

        while (fragmentCount - fragmentIndex > 0 && tries > 0) {
            int bytesRecvd = mUsbConn.bulkTransfer(mIrEndpointRead, fragmentBuf, 63, 500);

            if (bytesRecvd <= 0) {
                Log.w(TAG, "no response received");
                tries--;
                continue;
            }

            if (fragmentIndex == 0) {
                fragmentCount = fragmentBuf[3];
            }

            Log.w(TAG, "Got response: " + StringUtil.toHex(fragmentBuf, bytesRecvd));

            fragmentIndex++;
        }
    }


    private class TransmitterRunnable implements Runnable {
        @Override
        public void run() {
            try {
                transmitSignal(mSignal);
            } catch (Exception e) {
                Log.e(TAG, "failed to transmit", e);
            }
            if (!mHasTransmittedOnce)
                mHasTransmittedOnce = true;

            if (mWaitingForTransmission) {
                Log.d(TAG, "Posting new runnable");
                mHandler.postDelayed(this, getPeriodMillis());
            }
        }
    }
}
