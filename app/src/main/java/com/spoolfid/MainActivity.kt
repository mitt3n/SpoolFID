package com.spoolfid

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.spoolfid.ui.App
import com.spoolfid.ui.SpoolFIDTheme

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SpoolFIDTheme { App(vm) } }
    }

    override fun onResume() {
        super.onResume()
        val adapter = NfcAdapter.getDefaultAdapter(this)
        vm.nfcStatus = when {
            adapter == null -> NfcStatus.UNSUPPORTED
            !adapter.isEnabled -> NfcStatus.DISABLED
            !packageManager.hasSystemFeature("com.nxp.mifare") -> NfcStatus.NO_MIFARE
            else -> NfcStatus.OK
        }
        adapter?.enableReaderMode(
            this,
            this,
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
            null,
        )
    }

    override fun onPause() {
        super.onPause()
        NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
    }

    override fun onTagDiscovered(tag: Tag) {
        runOnUiThread { vm.onTag(tag) }
    }
}
