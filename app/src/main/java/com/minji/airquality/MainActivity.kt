package com.minji.airquality

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.Manifest
import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.LocationManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.registerForActivityResult
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.minji.airquality.databinding.ActivityMainBinding
import com.minji.airquality.retrofit.AirQualityResponse
import com.minji.airquality.retrofit.AirQualityService
import com.minji.airquality.retrofit.RetrofitConnection
import retrofit2.Callback
import java.io.IOException
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : AppCompatActivity() {

    lateinit var binding: ActivityMainBinding
    lateinit var locationProvider: LocationProvider


    private val PERMISSIONS_REQUEST_CODE = 100

    var latitude : Double? = 0.0
    var longitude : Double? = 0.0

    var REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    lateinit var getGPSPermissionLauncher: ActivityResultLauncher<Intent>

    val startMapActivityResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult(),
        object : ActivityResultCallback<ActivityResult> {
            override fun onActivityResult(result: ActivityResult) {
                if(result?.resultCode?:0 == Activity.RESULT_OK) {
                    latitude = result?.data?.getDoubleExtra("latitude", 0.0) ?: 0.0
                    longitude = result?.data?.getDoubleExtra("longitude", 0.0) ?: 0.0
                    updateUI()

                }
            }
        }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkAllPermissions()
        updateUI()
        setRefreshButton()
        setFab()
    }

    private fun updateUI() {
        locationProvider = LocationProvider(this@MainActivity)

        if(latitude == 0.0 && longitude == 0.0) {

            latitude= locationProvider.getLocationLatitude()
            longitude= locationProvider.getLocationLongitude()

        }
        if(latitude != null && longitude != null) {
            // 1.현재 위치 가져오고 UI 업데이트
            val address = getCurrentAddress(latitude!!, longitude!!)
            // address가 null이 아닐때 이렇게 설정
            address?.let{
                binding.tvLocationTitle.text = "${it.thoroughfare}"
                binding.tvLocationSubtitle.text = "${it.countryName} ${it.adminArea}"
            }
            // 2.미세먼지 농도 가져오고 UI 업데이트

            getAirQualityData(latitude!!, longitude!!)
        } else{
            Toast.makeText(this, "위도, 경도 정보를 가져올 수 없습니다.", Toast.LENGTH_LONG).show()
        }
    }

    private fun getAirQualityData(latitude: Double, longitude: Double) {
        // retrofit api 객체 생성
        var retrofitAPI = RetrofitConnection.getInstance().create(
            AirQualityService::class.java
        )
        // api 요청 실행
        retrofitAPI.getAirQualityData(
            latitude.toString(),
            longitude.toString(),
            "e7e9d20a-6437-430c-9e6d-420de797d5db"
        ).enqueue(object : Callback<AirQualityResponse> {   // 비동기 네트워크 요청 수행
            // 응답 성공
            override fun onResponse(
                call: retrofit2.Call<AirQualityResponse>,
                response: retrofit2.Response<AirQualityResponse>
            ) {
                if (response.isSuccessful) {
                    Toast.makeText(this@MainActivity, "최신 데이터 업데이트 완료!", Toast.LENGTH_LONG).show()
                    response.body()?.let{ updateAirUI(it)}


                } else {
                    Toast.makeText(this@MainActivity, "데이터를 가져오는데 실패했습니다.", Toast.LENGTH_LONG)
                        .show()
                }

            }
            // 응답 실패
            override fun onFailure(call: retrofit2.Call<AirQualityResponse>, t: Throwable) {
                // 오류 원인을 로그로 출력
                t.printStackTrace()
                Toast.makeText(this@MainActivity, "데이터를 가져오는데 실패했습니다.", Toast.LENGTH_LONG)
                    .show()
            }

        })

    }

    private fun updateAirUI(airQualityData: AirQualityResponse) {

        // 미세먼지 데이터 가져오기
        val pollutionData = airQualityData.data.current.pollution

        //수치를 지정
        binding.tvCount.text = pollutionData.aqius.toString()

        //측정된 날짜
        val dateTime = ZonedDateTime.parse(pollutionData.ts, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            .withZoneSameInstant(ZoneId.of("Asia/Seoul"))
            .toLocalDateTime()
        val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")


        binding.tvCheckTime.text = dateTime.format(dateFormatter).toString()

        when(pollutionData.aqius){
            in 0..50 -> {
                binding.tvTitle.text = "좋음"
                binding.imgBg.setImageResource(R.drawable.bg_good)
            }
            in 51..150 -> {
                binding.tvTitle.text = "보통"
                binding.imgBg.setImageResource(R.drawable.bg_soso)
            }
            in 151..200 -> {
                binding.tvTitle.text = "나쁨"
                binding.imgBg.setImageResource(R.drawable.bg_bad)
            }
            else -> {
                binding.tvTitle.text = "매우 나쁨"
                binding.imgBg.setImageResource(R.drawable.bg_worst)
            }

        }

    }

    private fun setRefreshButton() {
        binding.btnRefresh.setOnClickListener {
            updateUI()

        }
    }

    private fun setFab(){
        binding.fab.setOnClickListener{
            Log.d("DEBUG", "FloatingActionButton Clicked!")
            val intent = Intent(this, MapActivity::class.java)
            intent.putExtra("currentLat", latitude)
            intent.putExtra("currentLng", longitude)
            startMapActivityResult.launch(intent)   //ActivityResultLauncher<Intent> 객체
        }
    }

    private fun getCurrentAddress(latitude: Double, longitude: Double) : Address? {
        val geoCoder = Geocoder(this, Locale.KOREA)
        val addresses : List<Address>? = try {
            geoCoder.getFromLocation(latitude, longitude, 7)

        }catch(ioException : IOException){
            Toast.makeText(this, "지오코더 서비스 사용불가", Toast.LENGTH_LONG).show()
            return null

        }catch(illegalArgumentException : java.lang.IllegalArgumentException) {
            Toast.makeText(this, "잘못된 위도, 경도 입니다.", Toast.LENGTH_LONG).show()
            return null
        }
        if(addresses == null || addresses.size ==0 ) {
            Toast.makeText(this, "주소 미발견", Toast.LENGTH_LONG).show()
            return null

        }
        return addresses[0]
    }

    private fun checkAllPermissions() {
        if (!isLocationServicesAvailable()) {
            showDialogForLocationServiceSetting()

        } else {
            isRunTimePermissionsGranted()
        }
    }

    private fun isLocationServicesAvailable(): Boolean {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        return (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(
            LocationManager.NETWORK_PROVIDER
        ))
    }

    private fun isRunTimePermissionsGranted() {
        val hasFineLocationPermission = ContextCompat.checkSelfPermission(
            this@MainActivity,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        val hasCoarseLocationPermission = ContextCompat.checkSelfPermission(
            this@MainActivity,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (hasFineLocationPermission != PackageManager.PERMISSION_GRANTED || hasCoarseLocationPermission != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSIONS_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE && grantResults.size == REQUIRED_PERMISSIONS.size) {
            var checkResult = true

            for (result in grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    checkResult = false
                    break
                }
            }
            if (checkResult) {
                //위치 값을 가져올 수 있음
                updateUI()

            } else {
                Toast.makeText(this, "퍼미션이 거부되었습니다. 앱을 다시 실행하여 퍼미션을 허용해주세요.", Toast.LENGTH_LONG)
                    .show()
                finish()
            }
        }

    }

    private fun showDialogForLocationServiceSetting() {
        getGPSPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                if (isLocationServicesAvailable()) {
                    isRunTimePermissionsGranted()
                } else {
                    Toast.makeText(this, "위치 서비스를 사용할 수 없습니다.", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }

        val builder: AlertDialog.Builder = AlertDialog.Builder(this@MainActivity)
        builder.setTitle("위치 서비스 비활성화")
        builder.setMessage("앱을 사용하기 위해서는 위치 서비스가 필요합니다.")
        builder.setCancelable(true)
        builder.setPositiveButton("설정", DialogInterface.OnClickListener { dialogInterface, i ->
            val callGPSSettingIntent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            getGPSPermissionLauncher.launch(callGPSSettingIntent)
        })
        builder.setNegativeButton("취소", DialogInterface.OnClickListener { dialogInterface, i ->
            dialogInterface.cancel()
            Toast.makeText(this, "위치 서비스를 사용할 수 없습니다.", Toast.LENGTH_LONG).show()
            finish()
        })
        builder.create().show()
    }
}