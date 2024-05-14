package my.example.plugins

import my.example.Address

data class Delivery(
    val track: String,
    val weight: Double,
    val worth: Double,
    val origin: Address,
    val destinations: List<Address>
)