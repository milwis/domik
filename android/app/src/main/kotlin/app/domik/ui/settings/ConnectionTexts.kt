package app.domik.ui.settings

import androidx.annotation.StringRes
import app.domik.R
import app.domik.data.ConnectionTestFailure
import app.domik.data.FieldError

/** Komunikat o błędzie w polu formularza połączenia. */
@StringRes
fun FieldError.messageRes(): Int = when (this) {
    FieldError.HOST_EMPTY -> R.string.field_host_empty
    FieldError.HOST_INVALID -> R.string.field_host_invalid
    FieldError.PORT_INVALID -> R.string.field_port_invalid
    FieldError.SERIAL_EMPTY -> R.string.field_serial_empty
    FieldError.SERIAL_INVALID_CHARACTER -> R.string.field_serial_invalid_character
    FieldError.SERIAL_TOO_LONG -> R.string.field_serial_too_long
}

/** Zrozumiały powód nieudanego testu połączenia. */
@StringRes
fun ConnectionTestFailure.messageRes(): Int = when (this) {
    ConnectionTestFailure.HOST_UNREACHABLE -> R.string.connection_test_host_unreachable
    ConnectionTestFailure.CONNECTION_REFUSED -> R.string.connection_test_refused
    ConnectionTestFailure.TIMEOUT -> R.string.connection_test_timeout
    ConnectionTestFailure.CERTIFICATE_MISMATCH -> R.string.connection_test_certificate
    ConnectionTestFailure.TLS_FAILED -> R.string.connection_test_tls
    ConnectionTestFailure.NO_RESPONSE -> R.string.connection_test_no_response
    ConnectionTestFailure.CONNECTION_LOST -> R.string.connection_test_lost
    ConnectionTestFailure.OTHER -> R.string.connection_test_other
}
