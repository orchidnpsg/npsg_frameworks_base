/*
 * Copyright (C) 2016 The Android Open Source Project
 *
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
 */

package com.android.server.pm;

import android.app.AppGlobals;
import android.content.Context;
import android.content.Intent;
import android.content.pm.IOtaDexopt;
import android.content.pm.PackageParser;
import android.content.pm.PackageParser.Package;
import android.content.pm.ResolveInfo;
import android.os.Environment;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.util.ArraySet;
import android.util.Log;

import dalvik.system.DexFile;

import java.io.File;
import java.io.FileDescriptor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import static com.android.server.pm.Installer.DEXOPT_OTA;

/**
 * A service for A/B OTA dexopting.
 *
 * {@hide}
 */
public class OtaDexoptService extends IOtaDexopt.Stub {
    private final static String TAG = "OTADexopt";
    private final static boolean DEBUG_DEXOPT = true;

    private final Context mContext;
    private final PackageDexOptimizer mPackageDexOptimizer;
    private final PackageManagerService mPackageManagerService;

    // TODO: Evaluate the need for WeakReferences here.
    private List<PackageParser.Package> mDexoptPackages;

    public OtaDexoptService(Context context, PackageManagerService packageManagerService) {
        this.mContext = context;
        this.mPackageManagerService = packageManagerService;

        // Use the package manager install and install lock here for the OTA dex optimizer.
        mPackageDexOptimizer = new OTADexoptPackageDexOptimizer(packageManagerService.mInstaller,
                packageManagerService.mInstallLock, context);
    }

    public static OtaDexoptService main(Context context,
            PackageManagerService packageManagerService) {
        OtaDexoptService ota = new OtaDexoptService(context, packageManagerService);
        ServiceManager.addService("otadexopt", ota);

        return ota;
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ResultReceiver resultReceiver) throws RemoteException {
        (new OtaDexoptShellCommand(this)).exec(
                this, in, out, err, args, resultReceiver);
    }

    @Override
    public synchronized void prepare() throws RemoteException {
        if (mDexoptPackages != null) {
            throw new IllegalStateException("already called prepare()");
        }
        synchronized (mPackageManagerService.mPackages) {
            mDexoptPackages = PackageManagerServiceUtils.getPackagesForDexopt(
                    mPackageManagerService.mPackages.values(), mPackageManagerService);
        }
    }

    @Override
    public synchronized void cleanup() throws RemoteException {
        if (DEBUG_DEXOPT) {
            Log.i(TAG, "Cleaning up OTA Dexopt state.");
        }
        mDexoptPackages = null;
    }

    @Override
    public synchronized boolean isDone() throws RemoteException {
        if (mDexoptPackages == null) {
            throw new IllegalStateException("done() called before prepare()");
        }

        return mDexoptPackages.isEmpty();
    }

    @Override
    public synchronized void dexoptNextPackage() throws RemoteException {
        if (mDexoptPackages == null) {
            throw new IllegalStateException("dexoptNextPackage() called before prepare()");
        }
        if (mDexoptPackages.isEmpty()) {
            // Tolerate repeated calls.
            return;
        }

        PackageParser.Package nextPackage = mDexoptPackages.remove(0);

        if (DEBUG_DEXOPT) {
            Log.i(TAG, "Processing " + nextPackage.packageName + " for OTA dexopt.");
        }

        // Check for low space.
        // TODO: If apps are not installed in the internal /data partition, we should compare
        //       against that storage's free capacity.
        File dataDir = Environment.getDataDirectory();
        long lowThreshold = StorageManager.from(mContext).getStorageLowBytes(dataDir);
        if (lowThreshold == 0) {
            throw new IllegalStateException("Invalid low memory threshold");
        }
        long usableSpace = dataDir.getUsableSpace();
        if (usableSpace < lowThreshold) {
            Log.w(TAG, "Not running dexopt on " + nextPackage.packageName + " due to low memory: " +
                    usableSpace);
            return;
        }

        mPackageDexOptimizer.performDexOpt(nextPackage, null /* ISAs */, false /* useProfiles */,
                false /* extractOnly */);
    }

    private ArraySet<String> getPackageNamesForIntent(Intent intent, int userId) {
        List<ResolveInfo> ris = null;
        try {
            ris = AppGlobals.getPackageManager().queryIntentReceivers(
                    intent, null, 0, userId);
        } catch (RemoteException e) {
        }
        ArraySet<String> pkgNames = new ArraySet<String>(ris == null ? 0 : ris.size());
        if (ris != null) {
            for (ResolveInfo ri : ris) {
                pkgNames.add(ri.activityInfo.packageName);
            }
        }
        return pkgNames;
    }

    private static class OTADexoptPackageDexOptimizer extends
            PackageDexOptimizer.ForcedUpdatePackageDexOptimizer {

        public OTADexoptPackageDexOptimizer(Installer installer, Object installLock,
                Context context) {
            super(installer, installLock, context, "*otadexopt*");
        }

        @Override
        protected int adjustDexoptFlags(int dexoptFlags) {
            // Add the OTA flag.
            return dexoptFlags | DEXOPT_OTA;
        }

    }
}
