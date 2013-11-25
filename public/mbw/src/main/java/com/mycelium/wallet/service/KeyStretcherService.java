/*
 * Copyright 2013 Megion Research and Development GmbH
 *
 * Licensed under the Microsoft Reference Source License (MS-RSL)
 *
 * This license governs use of the accompanying software. If you use the software, you accept this license.
 * If you do not accept the license, do not use the software.
 *
 * 1. Definitions
 * The terms "reproduce," "reproduction," and "distribution" have the same meaning here as under U.S. copyright law.
 * "You" means the licensee of the software.
 * "Your company" means the company you worked for when you downloaded the software.
 * "Reference use" means use of the software within your company as a reference, in read only form, for the sole purposes
 * of debugging your products, maintaining your products, or enhancing the interoperability of your products with the
 * software, and specifically excludes the right to distribute the software outside of your company.
 * "Licensed patents" means any Licensor patent claims which read directly on the software as distributed by the Licensor
 * under this license.
 *
 * 2. Grant of Rights
 * (A) Copyright Grant- Subject to the terms of this license, the Licensor grants you a non-transferable, non-exclusive,
 * worldwide, royalty-free copyright license to reproduce the software for reference use.
 * (B) Patent Grant- Subject to the terms of this license, the Licensor grants you a non-transferable, non-exclusive,
 * worldwide, royalty-free patent license under licensed patents for reference use.
 *
 * 3. Limitations
 * (A) No Trademark License- This license does not grant you any rights to use the Licensor’s name, logo, or trademarks.
 * (B) If you begin patent litigation against the Licensor over patents that you think may apply to the software
 * (including a cross-claim or counterclaim in a lawsuit), your license to the software ends automatically.
 * (C) The software is licensed "as-is." You bear the risk of using it. The Licensor gives no express warranties,
 * guarantees or conditions. You may have additional consumer rights under your local laws which this license cannot
 * change. To the extent permitted under your local laws, the Licensor excludes the implied warranties of merchantability,
 * fitness for a particular purpose and non-infringement.
 */

package com.mycelium.wallet.service;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import com.google.common.base.Preconditions;
import com.mrd.bitlib.crypto.MrdExport;
import com.mrd.bitlib.crypto.MrdExport.V1.KdfParameters;
import com.mycelium.wallet.HttpErrorCollector;

import java.io.Serializable;

public class KeyStretcherService extends Service {

   private HttpErrorCollector _httpErrorCollector;

   public static class Status implements Serializable {
      private static final long serialVersionUID = 1L;

      public final boolean isStretching;
      public final double progress;
      public final boolean hasResult;
      public final boolean error;

      public Status(boolean isStretching, double progress, boolean hasResult, boolean error) {
         this.isStretching = isStretching;
         this.progress = progress;
         this.hasResult = hasResult;
         this.error = error;
      }
   }

   public static final int MSG_START = 1;
   public static final int MSG_GET_STATUS = 2;
   public static final int MSG_STATUS = 3;
   public static final int MSG_GET_RESULT = 3;
   public static final int MSG_RESULT = 4;
   public static final int MSG_TERMINATE = 5;
   private Messenger _serviceMessenger;
   private KdfParameters _kdfParameters;
   private Thread _stretcherThread;
   private MrdExport.V1.EncryptionParameters _result;
   private boolean _error;

   //@SuppressLint("HandlerLeak")
   private class IncomingHandler extends Handler {
      @Override
      public void handleMessage(Message msg) {
         switch (msg.what) {
            case MSG_START:
               Bundle bundle = Preconditions.checkNotNull(msg.getData());
               KdfParameters kdfParameters = (KdfParameters) bundle.getSerializable("kdfParameters");
               start(Preconditions.checkNotNull(kdfParameters));
               break;
            case MSG_GET_STATUS:
               sendStatus(msg.replyTo);
               break;
            case MSG_GET_RESULT:
               sendResult(msg.replyTo);
               break;
            case MSG_TERMINATE:
               terminate();
               stopSelf();
               break;
            default:
               super.handleMessage(msg);
         }
      }

      private void start(KdfParameters kdfParameters) {
         // We may have a stretching going on already, stop it
         terminate();
         _kdfParameters = kdfParameters;
         _stretcherThread = new Thread(new Runner());
         _stretcherThread.start();
      }

      private void sendStatus(Messenger messenger) {
         Message msg = Preconditions.checkNotNull(Message.obtain(null, MSG_STATUS));
         double progress = _kdfParameters == null ? 0 : _kdfParameters.getProgress();
         Bundle b = new Bundle();
         b.putSerializable("status", new Status(_stretcherThread == null, progress, _result != null, _error));
         msg.setData(b);
         try {
            messenger.send(msg);
         } catch (RemoteException e) {
            // We ignore any exceptions from the caller, they asked for an
            // update
            // and we don't care if they are not around anymore
            _httpErrorCollector.reportErrorToServer(e);
         }
      }

      private void sendResult(Messenger replyTo) {
         Message msg = Preconditions.checkNotNull(Message.obtain(null, MSG_RESULT));
         Bundle b = new Bundle();
         b.putSerializable("result", _result);
         msg.setData(b);
         try {
            replyTo.send(msg);
         } catch (RemoteException e) {
            // We ignore any exceptions from the caller, they asked for an
            // update
            // and we don't care if they are not around anymore
            _httpErrorCollector.reportErrorToServer(e);
         }
      }

      private void terminate() {
         Thread t = _stretcherThread;
         if (t != null) {
            KdfParameters params = _kdfParameters;
            if (params != null) {
               params.terminate();
            }
            try {
               t.join();
            } catch (InterruptedException ignored) {
               // Ignore
            }
         }
         _stretcherThread = null;
         _error = false;
         _result = null;
      }

   }

   @Override
   public IBinder onBind(Intent intent) {
      return _serviceMessenger.getBinder();
   }

   @Override
   public void onCreate() {
      _httpErrorCollector = HttpErrorCollector.registerInVM(getApplicationContext());
      _serviceMessenger = new Messenger(new IncomingHandler());
   }

   @Override
   public void onDestroy() {
      super.onDestroy();
   }

   private class Runner implements Runnable {

      @Override
      public void run() {
         try {
            _result = MrdExport.V1.EncryptionParameters.generate(_kdfParameters);
            _error = false;
         } catch (OutOfMemoryError e) {
            _result = null;
            _error = true;
            reportIgnoredError(e);
         } catch (InterruptedException ignored) {
            // This happens when it is terminated
            return;
         }
         _kdfParameters = null;
         _stretcherThread = null;
      }
   }

   private void reportIgnoredError(Throwable e) {
      RuntimeException msg = new RuntimeException("we caught an exception and displayed a message to the user: \n" + _error, e);
      _httpErrorCollector.reportErrorToServer(msg);
   }

}