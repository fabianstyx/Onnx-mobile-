package com.example.onnxsc.engine

import android.os.Parcel
import android.os.Parcelable

data class Prediction(
    val target: Boolean,
    val x: Float,
    val y: Float,
    val confidence: Float,
    val className: String = "",
    val classId: Int = -1,
    val width: Float = 0f,
    val height: Float = 0f
) : Parcelable {
    
    constructor(parcel: Parcel) : this(
        parcel.readByte() != 0.toByte(),
        parcel.readFloat(),
        parcel.readFloat(),
        parcel.readFloat(),
        parcel.readString() ?: "",
        parcel.readInt(),
        parcel.readFloat(),
        parcel.readFloat()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeByte(if (target) 1 else 0)
        parcel.writeFloat(x)
        parcel.writeFloat(y)
        parcel.writeFloat(confidence)
        parcel.writeString(className)
        parcel.writeInt(classId)
        parcel.writeFloat(width)
        parcel.writeFloat(height)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<Prediction> {
        override fun createFromParcel(parcel: Parcel): Prediction = Prediction(parcel)
        override fun newArray(size: Int): Array<Prediction?> = arrayOfNulls(size)
    }
    
    fun toJsonString(): String {
        return """{"target":$target,"x":$x,"y":$y,"confidence":$confidence,"className":"$className","classId":$classId,"width":$width,"height":$height}"""
    }
}
