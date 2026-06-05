package com.jamesburnetthq.ravensdr;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Log;

import java.util.HashMap;

public class UsbHelper {

    private static final String TAG = "UsbHelper";
    private static final String ACTION_USB_PERMISSION = "com.ravensdr.USB_PERMISSION";

    // Known RTL2832U vendor/product IDs
    private static final int VID_REALTEK = 0x0BDA;
    private static final int[] PIDS = { 0x2832, 0x2838, 0x2831, 0x2833, 0x2837, 0x2848 };

    public interface Listener {
        void onDeviceReady(int fileDescriptor, UsbDevice device);
        void onDeviceDetached();
        void onPermissionDenied();
        void onNoDeviceFound();
    }

    private final Context context;
    private final UsbManager usbManager;
    private final Listener listener;

    private UsbDevice pendingDevice;
    private UsbDeviceConnection activeConnection;

    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            // Fix 3: confirm receiver is firing at all
            Log.d(TAG, "permissionReceiver.onReceive: action=" + intent.getAction());
            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (granted && device != null) {
                    Log.i(TAG, "USB permission granted for " + device.getDeviceName());
                    openDevice(device);
                } else {
                    // Fix 4: only report denial, never re-request permission here
                    Log.w(TAG, "USB permission denied (granted=" + granted
                            + ", device=" + (device != null ? device.getDeviceName() : "null") + ")");
                    listener.onPermissionDenied();
                }
            }
        }
    };

    private final BroadcastReceiver detachReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null && isRtlSdr(device)) {
                    Log.i(TAG, "RTL-SDR detached: " + device.getDeviceName());
                    closeDevice();
                    listener.onDeviceDetached();
                }
            }
        }
    };

    public UsbHelper(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    }

    public void start() {
        // Fix 2: receivers MUST be registered before requestPermission() is ever called
        IntentFilter permFilter = new IntentFilter(ACTION_USB_PERMISSION);
        IntentFilter detachFilter = new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(permissionReceiver, permFilter, Context.RECEIVER_NOT_EXPORTED);
            context.registerReceiver(detachReceiver, detachFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(permissionReceiver, permFilter);
            context.registerReceiver(detachReceiver, detachFilter);
        }
        // Only scan for devices after receivers are registered
        findAndRequestDevice();
    }

    public void stop() {
        try { context.unregisterReceiver(permissionReceiver); } catch (Exception ignored) {}
        try { context.unregisterReceiver(detachReceiver); } catch (Exception ignored) {}
        closeDevice();
    }

    private void findAndRequestDevice() {
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        for (UsbDevice device : deviceList.values()) {
            if (isRtlSdr(device)) {
                Log.i(TAG, "Found RTL-SDR: VID=0x" + Integer.toHexString(device.getVendorId())
                        + " PID=0x" + Integer.toHexString(device.getProductId()));
                if (usbManager.hasPermission(device)) {
                    openDevice(device);
                } else {
                    requestPermission(device);
                }
                return;
            }
        }
        Log.w(TAG, "No RTL-SDR device found in USB device list");
        listener.onNoDeviceFound();
    }

    private void requestPermission(UsbDevice device) {
        // Fix 5: skip the dialog if we already have permission
        if (usbManager.hasPermission(device)) {
            Log.i(TAG, "Already have permission for " + device.getDeviceName() + ", opening directly");
            openDevice(device);
            return;
        }

        pendingDevice = device;
        // Fix 1: FLAG_MUTABLE required so the system can attach EXTRA_PERMISSION_GRANTED
        // to the PendingIntent when it is delivered back to the receiver.
        // FLAG_IMMUTABLE prevents this and breaks the permission flow on Android 12+.
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? PendingIntent.FLAG_MUTABLE : 0;
        PendingIntent pi = PendingIntent.getBroadcast(context, 0,
                new Intent(ACTION_USB_PERMISSION).setPackage(context.getPackageName()), flags);
        usbManager.requestPermission(device, pi);
        Log.i(TAG, "Requested USB permission for " + device.getDeviceName());
    }

    private void openDevice(UsbDevice device) {
        UsbDeviceConnection conn = usbManager.openDevice(device);
        if (conn == null) {
            Log.e(TAG, "Failed to open USB device");
            listener.onPermissionDenied();
            return;
        }

        // Claim interface 0 — librtlsdr needs exclusive control
        UsbInterface iface = device.getInterface(0);
        if (!conn.claimInterface(iface, true)) {
            Log.e(TAG, "Failed to claim USB interface 0");
            conn.close();
            listener.onPermissionDenied();
            return;
        }

        activeConnection = conn;
        int fd = conn.getFileDescriptor();
        Log.i(TAG, "USB device opened, fd=" + fd
                + " device=" + device.getDeviceName()
                + " VID=0x" + Integer.toHexString(device.getVendorId())
                + " PID=0x" + Integer.toHexString(device.getProductId()));
        if (fd == -1) {
            Log.e(TAG, "getFileDescriptor() returned -1 — connection is invalid");
            conn.close();
            activeConnection = null;
            listener.onPermissionDenied();
            return;
        }
        listener.onDeviceReady(fd, device);
    }

    private void closeDevice() {
        if (activeConnection != null) {
            activeConnection.close();
            activeConnection = null;
        }
    }

    // Called from MainActivity when the activity receives a USB_DEVICE_ATTACHED intent
    public void handleAttachedIntent(Intent intent) {
        UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (device != null && isRtlSdr(device)) {
            if (usbManager.hasPermission(device)) {
                openDevice(device);
            } else {
                requestPermission(device);
            }
        }
    }

    private static boolean isRtlSdr(UsbDevice device) {
        if (device.getVendorId() != VID_REALTEK) return false;
        int pid = device.getProductId();
        for (int p : PIDS) {
            if (p == pid) return true;
        }
        return false;
    }
}
