package com.kluoke.esp32ble
import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
class MainActivity:ComponentActivity(),BleProvisioningListener{
 private lateinit var ble:BleProvisioningManager;private var status by mutableStateOf("请连接 ESP32");private var aps by mutableStateOf(listOf<WifiAp>());private var ssid by mutableStateOf("");private var password by mutableStateOf("")
 private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){if(it.values.all{v->v})scanDevice()else status="需要蓝牙权限"}
 override fun onCreate(s:Bundle?){super.onCreate(s);ble=BleProvisioningManager(this,this);setContent{MaterialTheme{Column(Modifier.padding(20.dp)){Text(status);Button({permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT))}){Text("扫描并连接 ESP32")};Button({ble.scanWifi()}){Text("获取附近 Wi‑Fi")};LazyColumn(Modifier.weight(1f)){items(aps,key={it.ssid}){a->ListItem(headlineContent={Text(a.ssid)},supportingContent={Text(a.rssi.toString()+" dBm")},modifier=Modifier.clickable{ssid=a.ssid})}};OutlinedTextField(ssid,{ssid=it},label={Text("Wi‑Fi 名称")});OutlinedTextField(password,{password=it},label={Text("密码")});Button({ble.provision(ssid,password)}){Text("发送密码并配网")}}}}}
 private fun scanDevice(){val s=getSystemService(BluetoothManager::class.java).adapter.bluetoothLeScanner;status="正在扫描设备";s.startScan(object:ScanCallback(){override fun onScanResult(t:Int,r:ScanResult){if(r.device.name==BleProvisioningManager.DEVICE_NAME){s.stopScan(this);ble.connect(r.device)}}})}
 override fun onStatusChanged(v:String){runOnUiThread{status=v}};override fun onWifiApReceived(a:WifiAp){runOnUiThread{aps=(aps.filter{it.ssid!=a.ssid}+a).sortedByDescending{it.rssi}}};override fun onReady(){onStatusChanged("设备已准备好")};override fun onError(v:String){onStatusChanged(v)}
 override fun onDestroy(){ble.close();super.onDestroy()}
}