/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.facebot;

import android.util.*;

import java.io.*;
import java.util.HashMap;
import java.util.Map;
import android.os.RemoteException;
import android.util.Log;

/**
 * Injector for system API calls
 * <p>
 * {@hide}
 */
public class FaceBot {

    private static final String TAG = "FaceBot";
    private final IFaceBot mService;

    /**
     * @hide - hide this constructor because it has a parameter of type
     *       IFaceBot, which is a system private class. The right way to
     *       create an instance of this class is using the factory
     *       Context.getSystemService.
     */
    public FaceBot(IFaceBot service) {
        mService = service;
    }

    public String addEntry(String className, String methodName, String arguments, String result) {
        try {
            return mService.addEntry(className,methodName,arguments,result);
        } catch (RemoteException e) {
            Log.e(TAG, "addEntry: RemoteException", e);
            return result;
        }
    }

}
