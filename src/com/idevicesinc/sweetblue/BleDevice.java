package com.idevicesinc.sweetblue;

import static com.idevicesinc.sweetblue.BleDeviceState.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.content.Context;

import com.idevicesinc.sweetblue.BleDevice.ConnectionFailListener.AutoConnectUsage;
import com.idevicesinc.sweetblue.BleDevice.ConnectionFailListener.PE_Please;
import com.idevicesinc.sweetblue.BleDevice.ConnectionFailListener.Reason;
import com.idevicesinc.sweetblue.BleDevice.ReadWriteListener.Result;
import com.idevicesinc.sweetblue.BleDevice.ReadWriteListener.Status;
import com.idevicesinc.sweetblue.BleDevice.ReadWriteListener.Type;
import com.idevicesinc.sweetblue.BleDeviceConfig.BondFilter;
import com.idevicesinc.sweetblue.P_PollManager.E_NotifyState;
import com.idevicesinc.sweetblue.utils.*;
import com.idevicesinc.sweetblue.PA_StateTracker.E_Intent;

/**
 * This is the one other class you will use the most besides {@link BleManager}. It acts as a
 * BLE-specific abstraction for the {@link BluetoothDevice} and {@link BluetoothGatt} classes.
 * It does everything you would expect, like providing methods for connecting,
 * reading/writing characteristics, enabling notifications, etc.
 * <br><br>
 * Although instances of this class can be created explicitly through {@link BleManager#newDevice(String, String)}, usually
 * they're created implicitly by {@link BleManager} as a result of a scanning operation (e.g. {@link BleManager#startScan()}
 * and sent to you through {@link BleManager.DiscoveryListener#onDiscoveryEvent(BleManager.DiscoveryListener.DiscoveryEvent)}.
 */
public class BleDevice
{
	/**
	 * Provide an implementation of this callback to various methods like {@link BleDevice#read(UUID, ReadWriteListener)},
	 * {@link BleDevice#write(UUID, byte[], ReadWriteListener)}, {@link BleDevice#startPoll(UUID, Interval, ReadWriteListener)},
	 * {@link BleDevice#enableNotify(UUID, ReadWriteListener)}, {@link BleDevice#readRssi(ReadWriteListener)}, etc.
	 */
	public static interface ReadWriteListener
	{
		/**
		 * A value returned to {@link ReadWriteListener#onResult(Result)} by way of
		 * {@link Result#status} that indicates success of the operation or the reason for its failure.
		 * This enum is <i>not</i> meant to match up with {@link BluetoothGatt}.GATT_* values in any way.
		 * 
		 * @see Result#status
		 */
		public static enum Status
		{
			/**
			 * If {@link Result#type} {@link Type#isRead()} then {@link Result#data} will contain
			 * some data returned from the device. If type is {@link Type#WRITE} then {@link Result#data}
			 * was sent to the device.
			 */
			SUCCESS,
			
			/**
			 * Device is not {@link BleDeviceState#CONNECTED}.
			 */
			NOT_CONNECTED,
			
			/**
			 * Couldn't find a matching {@link Result#target} for the {@link Result#charUuid} (or {@link Result#descUuid} if {@link Result#target} is
			 * {@link Target#DESCRIPTOR}) which was given to {@link BleDevice#read(UUID, ReadWriteListener)},
			 * {@link BleDevice#write(UUID, byte[])}, etc. This most likely means that the internal call to {@link BluetoothGatt#discoverServices()}
			 * didn't find any {@link BluetoothGattService} that contained a {@link BluetoothGattCharacteristic} for {@link Result#charUuid}.
			 */
			NO_MATCHING_TARGET,
			
			/**
			 * You tried to do a read on a characteristic that is write-only, or vice-versa, or tried to read a notify-only characteristic, or etc., etc. 
			 */
			OPERATION_NOT_SUPPORTED,
			
			/**
			 * {@link BluetoothGatt#setCharacteristicNotification(BluetoothGattCharacteristic, boolean)} returned false for an unknown reason.
			 * This {@link Status} is only relevant for calls to {@link BleDevice#enableNotify(UUID, ReadWriteListener)} and
			 * {@link BleDevice#disableNotify(UUID, ReadWriteListener)} (or the various overloads).
			 */
			FAILED_TO_TOGGLE_NOTIFICATION,
			
			/**
			 * {@link BluetoothGattCharacteristic#setValue(byte[])} (or one of its overloads) or
			 * {@link BluetoothGattDescriptor#setValue(byte[])} (or one of its overloads) returned false.
			 */
			FAILED_TO_SET_VALUE_ON_TARGET,
			
			/**
			 * The call to {@link BluetoothGatt#readCharacteristic(BluetoothGattCharacteristic)} or {@link BluetoothGatt#writeCharacteristic(BluetoothGattCharacteristic)}
			 * or etc. returned {@link Boolean#FALSE} and thus failed immediately for unknown reasons. No good remedy for this...perhaps try {@link BleManager#dropTacticalNuke()}.
			 */
			FAILED_TO_SEND_OUT,
			
			/**
			 * The operation was cancelled by the device becoming {@link BleDeviceState#DISCONNECTED}.
			 */
			CANCELLED_FROM_DISCONNECT,
			
			/**
			 * The operation was cancelled because {@link BleManager} went {@link BleState#TURNING_OFF} and/or {@link BleState#OFF}.
			 * Note that if the user turns off BLE from their OS settings (airplane mode, etc.) then {@link Result#status} could potentially
			 * be {@link #CANCELLED_FROM_DISCONNECT} because SweetBlue might get the disconnect callback before the turning off callback.
			 * Basic testing has revealed that this is *not* the case, but you never know.
			 * <br><br>
			 * Either way, the device was or will be disconnected.
			 */
			CANCELLED_FROM_BLE_TURNING_OFF,
			
			/**
			 * Used either when {@link Result#type} {@link Type#isRead()} and the stack returned a null value for {@link BluetoothGattCharacteristic#getValue()} despite
			 * the operation being otherwise "successful", <i>or</i> {@link BleDevice#write(UUID, byte[])} (or overload(s) ) were called with a null data parameter.
			 * For the read case, the library will throw an {@link UhOh#READ_RETURNED_NULL}, but hopefully it was just a temporary glitch.
			 * If the problem persists try {@link BleManager#dropTacticalNuke()}.
			 */
			NULL_DATA,
			
			/**
			 * Used either when {@link Result#type} {@link Type#isRead()} and the operation was "successful" but returned a zero-length array for {@link Result#data},
			 * <i>or</i> {@link BleDevice#write(UUID, byte[])} (or overload(s) ) was called with a non-null but zero-length data parameter.
			 * Note that {@link Result#data} will be a zero-length array for all other error statuses as well, for example {@link #NO_MATCHING_TARGET}, {@link #NOT_CONNECTED}, etc.
			 * In other words it's never null.
			 */
			EMPTY_DATA,
			
			/**
			 * The operation failed in a "normal" fashion, at least relative to all the other strange ways an operation can fail. This means
			 * for example that {@link BluetoothGattCallback#onCharacteristicRead(BluetoothGatt, BluetoothGattCharacteristic, int)} returned
			 * a status code that was not zero. This could mean the device went out of range, was turned off, signal was disrupted, whatever.
			 * Often this means that the device is about to become {@link BleDeviceState#DISCONNECTED}.
			 */
			REMOTE_GATT_FAILURE,
			
			/**
			 * Operation took longer than {@link BleManagerConfig#DEFAULT_TASK_TIMEOUT} seconds so we cut it loose.
			 */
			TIMED_OUT;
			
			/**
			 * Returns true for {@link #CANCELLED_FROM_DISCONNECT} or {@link #CANCELLED_FROM_BLE_TURNING_OFF}.
			 */
			public boolean wasCancelled()
			{
				return this == CANCELLED_FROM_DISCONNECT || this == Status.CANCELLED_FROM_BLE_TURNING_OFF;
			}
		}
		
		/**
		 * The type of operation for a {@link Result} - read, write, poll, etc.
		 */
		public static enum Type
		{
			/**
			 * Associated with {@link BleDevice#read(UUID, ReadWriteListener)} or {@link BleDevice#readRssi(ReadWriteListener)}.
			 */
			READ,
			
			/**
			 * Associated with {@link BleDevice#write(UUID, byte[])} or {@link BleDevice#write(UUID, byte[], ReadWriteListener)}.
			 */
			WRITE,
			
			/**
			 * Associated with {@link BleDevice#startPoll(UUID, Interval, ReadWriteListener)} or 
			 * {@link BleDevice#startRssiPoll(Interval, ReadWriteListener)}.
			 */
			POLL,
			
			/**
			 * Associated with {@link BleDevice#enableNotify(UUID, ReadWriteListener)} when we actually get a notification.
			 */
			NOTIFICATION,
			
			/**
			 * Similar to {@link #NOTIFICATION}, kicked off from {@link BleDevice#enableNotify(UUID, ReadWriteListener)},
			 * but under the hood this is treated slightly differently.
			 */
			INDICATION,
			
			/**
			 * Associated with {@link BleDevice#startChangeTrackingPoll(UUID, Interval, ReadWriteListener)} or
			 * {@link BleDevice#enableNotify(UUID, Interval, ReadWriteListener)} where a force-read timeout is invoked.
			 */
			PSUEDO_NOTIFICATION,
			
			/**
			 * Associated with {@link BleDevice#enableNotify(UUID, ReadWriteListener)} and called when enabling the notification
			 * completes by writing to the Descriptor of the given {@link UUID}. {@link Status#SUCCESS} doesn't <i>necessarily</i> mean
			 * that notifications will definitely now work (there may be other issues in the underlying stack), but it's a reasonable guarantee.
			 */
			ENABLING_NOTIFICATION,
			
			/**
			 * Opposite of {@link #ENABLING_NOTIFICATION}.
			 */
			DISABLING_NOTIFICATION;
			
			/**
			 * Returns {@link Boolean#TRUE} for every {@link Type} except {@link #WRITE}, {@link #ENABLING_NOTIFICATION}, and {@link #DISABLING_NOTIFICATION}.
			 * Overall this convenience method is meant to tell you when we've <i>received</i> something from the device as opposed to writing something to it. 
			 */
			public boolean isRead()
			{
				return this != WRITE && this != ENABLING_NOTIFICATION && this != DISABLING_NOTIFICATION;
			}
			
			/**
			 * Returns true if <code>this</code> is {@link #NOTIFICATION}, {@link #PSUEDO_NOTIFICATION}, or {@link #INDICATION}.
			 */
			public boolean isNotification()
			{
				return this.isNativeNotification() || this == PSUEDO_NOTIFICATION;
			}
			
			/**
			 * Subset of {@link #isNotification()}, returns <code>true</code> only for {@link #NOTIFICATION} and {@link #INDICATION},
			 * i.e. only notifications who origin is an *actual* notification from the native stack.
			 */
			public boolean isNativeNotification()
			{
				return this == NOTIFICATION || this == INDICATION;
			}
		}
		
		/**
		 * The type of GATT object, provided by {@link Result#target}.
		 */
		public static enum Target
		{
			/**
			 * The {@link Result} returned has to do with a {@link BluetoothGattCharacteristic} under the hood.
			 */
			CHARACTERISTIC,
			
			/**
			 * The {@link Result} returned has to do with a {@link BluetoothGattDescriptor} under the hood.
			 */
			DESCRIPTOR,
			
			/**
			 * The {@link Result} is coming in from using {@link BleDevice#readRssi(ReadWriteListener)}
			 * or {@link BleDevice#startRssiPoll(Interval, ReadWriteListener)}.
			 */
			RSSI
		}
		
		/**
		 * Provides a bunch of information about a completed read, write, or notification.
		 */
		public static class Result
		{
			/**
			 * Value used in place of <code>null</code>, either indicating that {@link #descUuid}
			 * isn't used for the {@link Result} because {@link #target} is {@link Target#CHARACTERISTIC},
			 * or that both {@link #descUuid} and {@link #charUuid} aren't applicable because {@link #target}
			 * is {@link Target#RSSI}.
			 */
			public static final UUID NON_APPLICABLE_UUID = Uuids.INVALID;
			
			/**
			 * The {@link BleDevice} this {@link Result} is for.
			 */
			public BleDevice device(){  return m_device;  }
			private final BleDevice m_device;
			
			/**
			 * The type of operation, read, write, etc.
			 */
			public Type type(){  return m_type;  }
			private final Type m_type;
			
			/**
			 * The type of GATT object this {@link Result} is for, currently, characteristic, descriptor, or rssi.
			 */
			public Target target(){  return m_target;  }
			private final Target m_target;
			
			/**
			 * The {@link UUID} of the characteristic associated with this {@link Result}. This will always be
			 * a valid {@link UUID}, even if {@link #target} is {@link Target#DESCRIPTOR}.
			 */
			public UUID charUuid(){  return m_charUuid;  }
			private final UUID m_charUuid;
			
			/**
			 * The {@link UUID} of the descriptor associated with this {@link Result}. If {@link #target} is
			 * {@link Target#CHARACTERISTIC} then this will be referentially equal (i.e. you can use == to compare)
			 * to {@link #NON_APPLICABLE_UUID}.
			 */
			public UUID descUuid(){  return m_descUuid;  }
			private final UUID m_descUuid;
			
			/**
			 * The data sent to the peripheral if {@link Result#type} is {@link Type#WRITE},
			 * otherwise the data received from the peripheral if {@link Result#type} {@link Type#isRead()}.
			 * This will never be null. For error statuses it will be a zero-length array.
			 */
			public byte[] data(){  return m_data;  }
			private final byte[] m_data;
			
			/**
			 * This value gets updated as a result of a {@link BleDevice#readRssi(ReadWriteListener)} call.
			 * It will always be equivalent to {@link BleDevice#getRssi()} but is included here for convenience.
			 */
			public int rssi(){  return m_rssi;  }
			private final int m_rssi;
			
			/**
			 * Indicates either success or the type of failure. Some values of {@link Status} are not
			 * used for certain values of {@link Type}. For example a {@link Type#NOTIFICATION}
			 * cannot fail with {@link Status#TIMED_OUT}.
			 */
			public Status status(){  return m_status;  }
			private final Status m_status;
			
			/**
			 * Time spent "over the air" - so in the native stack, processing in the peripheral's embedded software, what have you.
			 */
			public Interval transitTime(){  return m_transitTime;  }
			private final Interval m_transitTime;
			
			/**
			 * Total time it took for the operation to complete, whether success or failure.
			 * This mainly includes time spent in the internal job queue plus {@link Result#transitTime}.
			 */
			public Interval totalTime(){  return m_totalTime;  }
			private final Interval m_totalTime;
			
			/**
			 * The native gatt status returned from the stack, if applicable. If the {@link #status} returned is,
			 * for example, {@link Status#NO_MATCHING_TARGET}, then the operation didn't even reach the point
			 * where a gatt status is provided, in which case this member is set to {@link BleDeviceConfig#GATT_STATUS_NOT_APPLICABLE}
			 * (value of {@value BleDeviceConfig#GATT_STATUS_NOT_APPLICABLE}). Otherwise it will be <code>0</code> for success or greater
			 * than <code>0</code> when there's an issue. <i>Generally</i> this value will only be meaningful when {@link #status}
			 * is {@link Status#SUCCESS} or {@link Status#REMOTE_GATT_FAILURE}. There are also some cases where this will be 0 for
			 * success but {@link #status} is for example {@link Status#NULL_DATA} - in other words the underlying stack deemed the 
			 * operation a success but SweetBlue disagreed. For this reason it's recommended to treat this value as a debugging tool
			 * and use {@link #status} for actual application logic if possible.
			 * <br><br>
			 * See {@link BluetoothGatt} for its static <code>GATT_*</code> status code members.
			 * Also see the source code (commented out) of {@link PS_GattStatus} for SweetBlue's more comprehensive internal
			 * reference list of gatt status values. This list may not be totally accurate or up-to-date, nor may it match GATT_ values
			 * used by the bluetooth stack on your phone.
			 */
			public int gattStatus(){  return m_gattStatus;  }
			private final int m_gattStatus;
			
			Result
			(
				BleDevice device, UUID charUuid, UUID descUuid, Type type, Target target,
				byte[] data, Status status, int gattStatus, double totalTime, double transitTime
			)
			{
				this.m_device = device;
				this.m_charUuid = charUuid != null ? charUuid : NON_APPLICABLE_UUID;;
				this.m_descUuid = descUuid != null ? descUuid : NON_APPLICABLE_UUID;
				this.m_type = type;
				this.m_target = target;
				this.m_status = status;
				this.m_gattStatus = gattStatus;
				this.m_totalTime = Interval.secs(totalTime);
				this.m_transitTime = Interval.secs(transitTime);
				
				this.m_data = data != null ? data : EMPTY_BYTE_ARRAY;
				this.m_rssi = device.getRssi();
			}
			
			Result(BleDevice device, Type type, int rssi, Status status, int gattStatus, double totalTime, double transitTime)
			{
				this.m_device = device;
				this.m_charUuid = NON_APPLICABLE_UUID;;
				this.m_descUuid = NON_APPLICABLE_UUID;
				this.m_type = type;
				this.m_target = Target.RSSI;
				this.m_status = status;
				this.m_gattStatus = gattStatus;
				this.m_totalTime = Interval.secs(totalTime);
				this.m_transitTime = Interval.secs(transitTime);
				
				this.m_data = EMPTY_BYTE_ARRAY;
				this.m_rssi = status == Status.SUCCESS ? rssi : device.getRssi();
			}
			
			/**
			 * Convenience method for checking if {@link Result#status} equals {@link Status#SUCCESS}.
			 */
			public boolean wasSuccess()
			{
				return status() == Status.SUCCESS;
			}
			
			/**
			 * Forwards {@link Status#wasCancelled()}.
			 */
			public boolean wasCancelled()
			{
				return status().wasCancelled();
			}
			
			/** 
			 * Forwards {@link Type#isNotification()}.
			 */
			public boolean isNotification()
			{
				return type().isNotification();
			}
			
			/**
			 * Forwards {@link Type#isRead()}.
			 */
			public boolean isRead()
			{
				return type().isRead();
			}
			
			/**
			 * Returns the first byte from {@link #data()}, or 0x0 if not available.
			 */
			public byte data_byte()
			{
				return data().length > 0 ? data()[0] : 0x0;
			}
			
			@Override public String toString()
			{
				if( target() == Target.RSSI )
				{
					return Utils.toString
					(
						"status",		status(),
						"type",			type(),
						"target",		target(),
						"rssi",			rssi(),
						"gattStatus",	device().m_mngr.getLogger().gattStatus(gattStatus())
					);
				}
				else
				{
					return Utils.toString
					(
						"status",		status(),
						"type",			type(),
						"charUuid",		device().m_mngr.getLogger().charName(charUuid()),
						"data",			data(),
						"gattStatus",	device().m_mngr.getLogger().gattStatus(gattStatus())
					);
				}
			}
		}
		
		/**
		 * Called when a read or write is complete or when a notification comes in or when a notification is enabled/disabled.
		 */
		void onResult(Result result);
	}
	
	/**
	 * Provide an implementation to {@link BleDevice#setListener_State(StateListener)} and/or
	 * {@link BleManager#setListener_DeviceState(BleDevice.StateListener)} to receive state change events.
	 * 
	 * @see BleDeviceState
	 * @see BleDevice#setListener_State(StateListener)
	 */
	public static interface StateListener
	{
		/**
		 * Subclass that adds the device field.
		 */
		public static class ChangeEvent extends State.ChangeEvent
		{
			/**
			 * The device undergoing the state change.
			 */
			public BleDevice device(){  return m_device;  }
			private final BleDevice m_device;
			
			ChangeEvent(BleDevice device, int oldStateBits, int newStateBits, int intentMask)
			{
				super(oldStateBits, newStateBits, intentMask);
				
				this.m_device = device;
			}
		}
		
		/**
		 * Called when a device's bitwise {@link BleDeviceState} changes. As many bits as possible are flipped at the same time.
		 */
		void onStateChange(ChangeEvent event);
	}
	
	/**
	 * Provide an implementation of this callback to {@link BleDevice#setListener_ConnectionFail(ConnectionFailListener)}.
	 * 
	 * @see DefaultConnectionFailListener
	 * @see BleDevice#setListener_ConnectionFail(ConnectionFailListener)
	 */
	public static interface ConnectionFailListener
	{
		/**
		 * The reason for the connection failure.
		 */
		public static enum Reason
		{
			/**
			 * Used in place of Java's built-in <code>null</code> wherever needed. As of now, the {@link Info#reason()} given to
			 * {@link ConnectionFailListener#onConnectionFail(Info)} will *never* be {@link Reason#NULL}.
			 */
			NULL,
			
			/**
			 * Couldn't connect through {@link BluetoothDevice#connectGatt(android.content.Context, boolean, BluetoothGattCallback)}
			 * because it returned <code>null</code>.
			 */
			NATIVE_CONNECTION_FAILED_IMMEDIATELY,
			
			/**
			 * Eventually got a callback to {@link BluetoothGattCallback#onConnectionStateChange(BluetoothGatt, int, int)} but {@link Info#gattStatus}
			 * did not indicate success.
			 */
			NATIVE_CONNECTION_FAILED_EVENTUALLY,
			
			/**
			 * {@link BluetoothDevice#connectGatt(android.content.Context, boolean, BluetoothGattCallback)} took longer than
			 * {@link BleDeviceConfig#timeoutForConnection}.
			 */
			NATIVE_CONNECTION_TIMED_OUT,
			
			/**
			 * {@link BluetoothGatt#discoverServices()} returned false and thus failed immediately.
			 */
			GETTING_SERVICES_FAILED_IMMEDIATELY,
			
			/**
			 * {@link BluetoothGattCallback#onServicesDiscovered(BluetoothGatt, int)} returned with a non-zero gattStatus.
			 */
			GETTING_SERVICES_FAILED_EVENTUALLY,
			
			/**
			 * {@link BluetoothGatt#discoverServices()} took longer than {@link BleDeviceConfig#timeoutForDiscoveringServices}.
			 */
			GETTING_SERVICES_TIMED_OUT,
			
			/**
			 * The {@link BleTransaction} instance passed to {@link BleDevice#connect(BleTransaction.Auth)} or
			 * {@link BleDevice#connect(BleTransaction.Auth, BleTransaction.Init)} failed through {@link BleTransaction#fail()}.
			 */
			AUTHENTICATION_FAILED,
			
			/**
			 * {@link BleTransaction} instance passed to {@link BleDevice#connect(BleTransaction.Init)} or
			 * {@link BleDevice#connect(BleTransaction.Auth, BleTransaction.Init)} failed through {@link BleTransaction#fail()}.
			 */
			INITIALIZATION_FAILED,
			
			/**
			 * Remote peripheral randomly disconnected sometime during the connection process. Similar to {@link #NATIVE_CONNECTION_FAILED_EVENTUALLY}
			 * but only occurs after the device is {@link BleDeviceState#CONNECTED} and we're going through {@link BleDeviceState#GETTING_SERVICES},
			 * or {@link BleDeviceState#AUTHENTICATING}, or what have you. It might from the device turning off, or going out of range, or any other
			 * random reason.
			 */
			ROGUE_DISCONNECT,
			
			/**
			 * {@link BleDevice#disconnect()} was called sometime during the connection process.
			 */
			EXPLICIT_DISCONNECT,
			
			/**
			 * {@link BleManager#dropTacticalNuke()} or {@link BleManager#turnOff()} (or overloads) were called sometime during the connection process.
			 * Basic testing reveals that this value will also be used when a user turns off BLE by going through their OS settings, airplane mode, etc.,
			 * but it's not absolutely *certain* that this behavior is consistent across phones. For example there might be a phone that kills all
			 * connections before going through the ble turn-off process, in which case SweetBlue doesn't know the difference and {@link #ROGUE_DISCONNECT} will be used.
			 */
			BLE_TURNING_OFF;
			
			/**
			 * Returns true for {@link #EXPLICIT_DISCONNECT} or {@link #BLE_TURNING_OFF}.
			 */
			public boolean wasCancelled()
			{
				return this == EXPLICIT_DISCONNECT || this == BLE_TURNING_OFF;
			}
		}
		
		/**
		 * Describes usage of the <code>autoConnect</code> parameter for {@link BluetoothDevice#connectGatt(Context, boolean, android.bluetooth.BluetoothGattCallback)}.
		 */
		public static enum AutoConnectUsage
		{
			/**
			 * Used when we didn't start the connection process, i.e. it came out of nowhere. Rare case but can happen,
			 * for example after SweetBlue considers a connect timed out after {@link BleDeviceConfig#timeoutForConnection}
			 * seconds, but then it somehow does come in (shouldn't happen but who knows).
			 */
			UNKNOWN,
			
			/**
			 * Usage is not applicable to the {@link Info#reason} given.
			 */
			NOT_APPLICABLE,
			
			/**
			 * <code>autoConnect</code> was used.
			 */
			USED,
			
			/**
			 * <code>autoConnect</code> was not used.
			 */
			NOT_USED;
		}

		static enum PE_Please
		{
			RETRY, RETRY_WITH_AUTOCONNECT_TRUE, RETRY_WITH_AUTOCONNECT_FALSE, DO_NOT_RETRY;
		}
		
		/**
		 * Return value for {@link ConnectionFailListener#onConnectionFail(Info)}. Generally you will only return {@link #retry()}
		 * or {@link #doNotRetry()}, but there are more advanced options as well.
		 */
		public static class Please
		{
			private final PE_Please m_please;
			
			private Please(PE_Please please)
			{
				m_please = please;
			}
			
			PE_Please please()
			{
				return m_please;
			}
			
			/**
			 * Return this to retry the connection, continuing the connection fail retry loop.
			 * <code>autoConnect</code> passed to {@link BluetoothDevice#connectGatt(Context, boolean, android.bluetooth.BluetoothGattCallback)}
			 * will be false or true based on what has worked in the past, or on {@link BleDeviceConfig#alwaysUseAutoConnect}.
			 */
			public static Please retry()
			{
				return new Please(PE_Please.RETRY);
			}
			
			/**
			 * Return this to stop the connection fail retry loop.
			 */
			public static Please doNotRetry()
			{
				return new Please(PE_Please.DO_NOT_RETRY);
			}
			
			/**
			 * Same as {@link #RETRY}, but <code>autoConnect=true</code> will be passed to {@link BluetoothDevice#connectGatt(Context, boolean, android.bluetooth.BluetoothGattCallback)}.
			 * See more discussion at {@link BleDeviceConfig#alwaysUseAutoConnect}.
			 */
			public static Please retryWithAutoConnectTrue()
			{
				return new Please(PE_Please.RETRY_WITH_AUTOCONNECT_TRUE);
			}
			
			/**
			 * Same as {@link #RETRY_WITH_AUTOCONNECT_TRUE} but forces <code>autoConnect=false</code>.
			 */
			public static Please retryWithAutoConnectFalse()
			{
				return new Please(PE_Please.RETRY_WITH_AUTOCONNECT_FALSE);
			}
			
			/**
			 * Returns <code>true</code> for everything except {@link #doNotRetry()}.
			 */
			public boolean isRetry()
			{
				return m_please != PE_Please.DO_NOT_RETRY;
			}
		}
		
		/**
		 * Structure passed to {@link ConnectionFailListener#onConnectionFail(Info)} to provide more info about how/why the connection failed.
		 */
		public static class Info
		{			
			/**
			 * The {@link BleDevice} this {@link Info} is for.
			 */
			public BleDevice device(){  return m_device;  }
			private final BleDevice m_device;
			
			/**
			 * Why the connection failed.
			 */
			public Reason reason(){  return m_reason;  }
			private final Reason m_reason;
			
			/**
			 * The failure count so far. This will start at 1 and keep incrementing for more failures.
			 */
			public int failureCountSoFar(){  return m_failureCountSoFar;  }
			private final int m_failureCountSoFar;
			
			/**
			 *  How long the last connection attempt took before failing.
			 */
			public Interval attemptTime_latest(){  return m_latestAttemptTime;  }
			private final Interval m_latestAttemptTime;
			
			/**
			 * How long it's been since {@link BleDevice#connect()} (or overloads) were initially called.
			 */
			public Interval attemptTime_total(){  return m_totalAttemptTime;  }
			private final Interval m_totalAttemptTime;
			
			/**
			 * The gattStatus returned, if applicable, from native callbacks like {@link BluetoothGattCallback#onConnectionStateChange(BluetoothGatt, int, int)}
			 * or {@link BluetoothGattCallback#onServicesDiscovered(BluetoothGatt, int)}. If not applicable, for example if {@link Info#reason} is
			 * {@link Reason#EXPLICIT_DISCONNECT}, then this is set to {@link BleDeviceConfig#GATT_STATUS_NOT_APPLICABLE}.
			 * <br><br>
			 * See {@link Result#gattStatus} for more information about gatt status codes in general.
			 * 
			 * @see Result#gattStatus
			 */
			public int gattStatus(){  return m_gattStatus;  }
			private final int m_gattStatus;
			
			/**
			 * The highest state reached by the latest connection attempt.
			 */
			public BleDeviceState highestStateReached_latest(){  return m_highestStateReached_latest;  }
			private final BleDeviceState m_highestStateReached_latest;
			
			/**
			 * The highest state reached during the whole connection attempt cycle.
			 * <br><br>
			 * TIP: You can use this to keep the visual feedback in your connection progress UI "bookmarked"
			 * 		while the connection retries and goes through previous states again.
			 */
			public BleDeviceState highestStateReached_total(){  return m_highestStateReached_total;  }
			private final BleDeviceState m_highestStateReached_total;
			
			/**
			 * Whether <code>autoConnect=true</code> was passed to {@link BluetoothDevice#connectGatt(Context, boolean, android.bluetooth.BluetoothGattCallback)}.
			 * See more discussion at {@link BleDeviceConfig#alwaysUseAutoConnect}.
			 */
			public AutoConnectUsage autoConnectUsage(){  return m_autoConnectUsage;  }
			private final AutoConnectUsage m_autoConnectUsage;
			
			Info
			(
				BleDevice device, Reason reason, int failureCountSoFar, Interval latestAttemptTime, Interval totalAttemptTime,
				int gattStatus, BleDeviceState highestStateReached, BleDeviceState highestStateReached_total, AutoConnectUsage autoConnectUsage
			)
			{
				this.m_device = device;
				this.m_reason = reason;
				this.m_failureCountSoFar = failureCountSoFar;
				this.m_latestAttemptTime = latestAttemptTime;
				this.m_totalAttemptTime = totalAttemptTime;
				this.m_gattStatus = gattStatus;
				this.m_highestStateReached_latest = highestStateReached != null ? highestStateReached : BleDeviceState.NULL;
				this.m_highestStateReached_total = highestStateReached_total != null ? highestStateReached_total : BleDeviceState.NULL;
				this.m_autoConnectUsage = autoConnectUsage;
				
				m_device.getManager().ASSERT(highestStateReached != null, "highestState_latest shouldn't be null.");
				m_device.getManager().ASSERT(highestStateReached_total != null, "highestState_total shouldn't be null.");
			}
			
			static Info NULL(BleDevice device)
			{
				return new Info(device, Reason.NULL, 0, Interval.DISABLED, Interval.DISABLED, BleDeviceConfig.GATT_STATUS_NOT_APPLICABLE, BleDeviceState.NULL, BleDeviceState.NULL, AutoConnectUsage.UNKNOWN);
			}
			
			/**
			 * Returns whether this {@link Info} instance is a "dummy" value. For now used for {@link BleDeviceConfig.ReconnectLoop.Info#connectionFailInfo()}
			 * in certain situations.
			 */
			public boolean isNull()
			{
				return reason() == Reason.NULL;
			}
			
			@Override public String toString()
			{
				if( isNull() )
				{
					return Reason.NULL.name();
				}
				else
				{
					return Utils.toString
					(
						"reason",				reason(),
						"gattStatus",			device().m_mngr.getLogger().gattStatus(gattStatus()),
						"failureCountSoFar",	failureCountSoFar()
					);
				}
			}
		}
		
		/**
		 * Return value is ignored if device is either {@link BleDeviceState#ATTEMPTING_RECONNECT} or reason {@link Reason#wasCancelled()}.
		 * If the device is {@link BleDeviceState#ATTEMPTING_RECONNECT} then authority is deferred to {@link BleDeviceConfig.ReconnectLoop}.
		 * Otherwise, this method offers a more convenient way of retrying a connection, as opposed to manually doing it yourself. It also
		 * lets the library handle things in a slightly more optimized/cleaner fashion and so is recommended for that reason also.
		 * <br><br>
		 * NOTE that this callback gets fired *after* {@link StateListener} lets you know that the device is {@link BleDeviceState#DISCONNECTED}.
		 * <br><br>
		 * The time parameters passed to this method are of optional use to you to decide if connecting again is worth it. For example if you've 
		 * been trying to connect for 10 seconds already, chances are that another connection attempt probably won't work.
		 */
		Please onConnectionFail(Info info);
	}
	
	/**
	 * Default implementation of {@link ConnectionFailListener} that attempts a certain number of retries.
	 * An instance of this class is set by default for all new {@link BleDevice} instances using {@link BleDevice.DefaultConnectionFailListener#DEFAULT_CONNECTION_FAIL_RETRY_COUNT}.
	 * Use {@link BleDevice#setListener_ConnectionFail(ConnectionFailListener)} to override the default behavior.
	 * 
	 * @see ConnectionFailListener
	 * @see BleDevice#setListener_ConnectionFail(ConnectionFailListener)
	 */
	public static class DefaultConnectionFailListener implements ConnectionFailListener
	{
		/**
		 * The default retry count provided to {@link DefaultConnectionFailListener}. So if you were to call
		 * {@link BleDevice#connect()} and all connections failed, in total the library would try to connect
		 * {@value #DEFAULT_CONNECTION_FAIL_RETRY_COUNT}+1 times.
		 * 
		 * @see DefaultConnectionFailListener
		 */
		public static final int DEFAULT_CONNECTION_FAIL_RETRY_COUNT = 2;
		
		/**
		 * The default connection fail limit past which {@link DefaultConnectionFailListener} will start returning {@link PE_Please#RETRY_WITH_AUTOCONNECT_TRUE}.
		 */
		public static final int DEFAULT_FAIL_COUNT_BEFORE_USING_AUTOCONNECT = 2;
		
		private final int m_retryCount;
		private final int m_failCountBeforeUsingAutoConnect;
		
		public DefaultConnectionFailListener()
		{
			this(DEFAULT_CONNECTION_FAIL_RETRY_COUNT, DEFAULT_FAIL_COUNT_BEFORE_USING_AUTOCONNECT);
		}
		
		public DefaultConnectionFailListener(int retryCount, int failCountBeforeUsingAutoConnect)
		{
			m_retryCount = retryCount;
			m_failCountBeforeUsingAutoConnect = failCountBeforeUsingAutoConnect;
		}
		
		public int getRetryCount()
		{
			return m_retryCount;
		}
		
		@Override public Please onConnectionFail(Info info)
		{
			//--- DRK > Not necessary to check this ourselves, just being explicit.
			if( info.reason().wasCancelled() || info.device().is(ATTEMPTING_RECONNECT) )
			{
				return Please.doNotRetry();
			}
			
			if( info.failureCountSoFar() <= m_retryCount )
			{
				if( info.failureCountSoFar() >= m_failCountBeforeUsingAutoConnect )
				{
					return Please.retryWithAutoConnectTrue();
				}
				else
				{
					if( info.reason() == Reason.NATIVE_CONNECTION_TIMED_OUT )
					{
						if( info.autoConnectUsage() == AutoConnectUsage.USED )
						{
							return Please.retryWithAutoConnectFalse();
						}
						else if( info.autoConnectUsage() == AutoConnectUsage.NOT_USED )
						{
							return Please.retryWithAutoConnectTrue();
						}
						else
						{
							return Please.retry();
						}
					}
					else
					{
						return Please.retry();
					}
				}
			}
			else
			{
				return Please.doNotRetry();
			}
		}
	}
	
	static ConnectionFailListener DEFAULT_CONNECTION_FAIL_LISTENER = new DefaultConnectionFailListener();
	
	private static final UUID[]				EMPTY_UUID_ARRAY	= new UUID[0];
	private static final ArrayList<UUID>	EMPTY_LIST			= new ArrayList<UUID>();
			static final byte[]				EMPTY_BYTE_ARRAY	= new byte[0];
			

	final Object m_threadLock = new Object();
	
	final P_NativeDeviceWrapper m_nativeWrapper;
	
	private double m_timeSinceLastDiscovery;
	
			final P_BleDevice_Listeners m_listeners;
	private final P_ServiceManager m_serviceMngr;
	private final P_DeviceStateTracker m_stateTracker;
	private final P_PollManager m_pollMngr;
	
	private final BleManager m_mngr;
	private final P_Logger m_logger;
	private final P_TaskQueue m_queue;
			final P_TransactionManager m_txnMngr;
	private final P_ReconnectManager m_reconnectMngr;
	private final P_ConnectionFailManager m_connectionFailMngr;
	private final P_RssiPollManager m_rssiPollMngr;
	private final P_Task_Disconnect m_dummyDisconnectTask;
	
	private TimeEstimator m_writeTimeEstimator;
	private TimeEstimator m_readTimeEstimator;
	
	private final PA_Task.I_StateListener m_taskStateListener;
	
	private final BleDeviceOrigin m_origin;
	
	private int m_rssi = 0;
	private Integer m_knownTxPower = null;
	private List<UUID> m_advertisedServices = EMPTY_LIST;
	private byte[] m_scanRecord = EMPTY_BYTE_ARRAY;
	
	private boolean m_useAutoConnect = false;
	private boolean m_alwaysUseAutoConnect = false;
	
	private Boolean m_lastConnectOrDisconnectWasUserExplicit = null;
	private boolean m_lastDisconnectWasBecauseOfBleTurnOff = false; 
	
	private BleDeviceConfig m_config = null;
	
	/**
	 * Field for app to associate any data it wants with instances of this class
	 * instead of having to subclass or manage associative hash maps or something.
	 */
	public Object appData;
	
	
	BleDevice(BleManager mngr, BluetoothDevice device_native, String normalizedName, String nativeName, BleDeviceOrigin origin, BleDeviceConfig config_nullable)
	{
		m_mngr = mngr;
		m_origin = origin;
		setConfig(config_nullable);
		m_nativeWrapper = new P_NativeDeviceWrapper(this, device_native, normalizedName, nativeName);
		m_queue = m_mngr.getTaskQueue();
		m_listeners = new P_BleDevice_Listeners(this);
		m_logger = m_mngr.getLogger();
		m_serviceMngr = new P_ServiceManager(this);
		m_stateTracker = new P_DeviceStateTracker(this);
		m_stateTracker.set(E_Intent.IMPLICIT, BleDeviceState.UNDISCOVERED, true, BleDeviceState.DISCONNECTED, true);
		m_pollMngr = new P_PollManager(this);
		m_txnMngr = new P_TransactionManager(this);
		m_taskStateListener = m_listeners.m_taskStateListener;
		m_reconnectMngr = new P_ReconnectManager(this);
		m_connectionFailMngr = new P_ConnectionFailManager(this, m_reconnectMngr);
		m_rssiPollMngr = new P_RssiPollManager(this);
		m_dummyDisconnectTask = new P_Task_Disconnect(this, null, /*explicit=*/false, PE_TaskPriority.FOR_EXPLICIT_BONDING_AND_CONNECTING);
	}
	
	private void clear_discovery()
	{
//		clear_common();
//		
//		initEstimators();
	}
	
	private void clear_common()
	{
		m_connectionFailMngr.setListener(null);
		m_stateTracker.setListener(null);
		m_config = null;
	}
	
	private void clear_undiscovery()
	{
//		clear_common();
	}
	
	/**
	 * Optionally sets overrides for any custom options given to {@link BleManager#get(android.content.Context, BleManagerConfig)}
	 * for this individual device. 
	 */
	public void setConfig(BleDeviceConfig config_nullable)
	{
		m_config = config_nullable == null ? null : config_nullable.clone();
		
		initEstimators();
		
		//--- DRK > Not really sure how this config option should be interpreted, but here's a first stab for now.
		//---		Fringe enough use case that I don't think it's really a big deal.
		boolean alwaysUseAutoConnect = BleDeviceConfig.bool(conf_device().alwaysUseAutoConnect, conf_mngr().alwaysUseAutoConnect);
		if( alwaysUseAutoConnect )
		{
			m_alwaysUseAutoConnect = m_useAutoConnect = true;
		}
		else
		{
			m_alwaysUseAutoConnect = false;
		}
	}
	
	private void initEstimators()
	{
		Integer nForAverageRunningWriteTime = BleDeviceConfig.integer(conf_device().nForAverageRunningWriteTime, conf_mngr().nForAverageRunningWriteTime);
		m_writeTimeEstimator = nForAverageRunningWriteTime == null ? null : new TimeEstimator(nForAverageRunningWriteTime);
		
		Integer nForAverageRunningReadTime = BleDeviceConfig.integer(conf_device().nForAverageRunningReadTime, conf_mngr().nForAverageRunningReadTime);
		m_readTimeEstimator = nForAverageRunningReadTime == null ? null : new TimeEstimator(nForAverageRunningReadTime);
	}
	
	BleDeviceConfig conf_device()
	{
		return m_config != null ? m_config : conf_mngr();
	}
	
	BleManagerConfig conf_mngr()
	{
		return m_mngr.m_config;
	}
	
	public BleDeviceOrigin getOrigin()
	{
		return m_origin;
	}
	
	/**
	 * This enum gives you an indication of the last interaction with a device across app sessions or
	 * in-app BLE {@link BleManagerState#OFF}->{@link BleManagerState#ON} cycles or undiscovery->rediscovery, which basically means how it
	 * was last {@link BleDeviceState#DISCONNECTED}.
	 * <br><br>
	 * If {@link State.ChangeIntent#NULL}, then the last disconnect is unknown because (a) device has never been seen before, (b)
	 * reason for disconnect was app being killed and {@link BleDeviceConfig#manageLastDisconnectOnDisk}
	 * was <code>false</code>, (c) app user cleared app data between app sessions, (d) etc., etc.
	 * <br><br>
	 * If {@link State.ChangeIntent#UNINTENTIONAL}, then from a user experience perspective, the user may not have wanted the
	 * disconnect to happen, and thus *probably* would want to be automatically connected again
	 * as soon as the device is discovered.
	 * <br><br>
	 * If {@link State.ChangeIntent#INTENTIONAL}, then last reason the device was {@link BleDeviceState#DISCONNECTED} was because
	 * {@link BleDevice#disconnect()} was called, which most-likely means the user doesn't want to automatically connect to this device again.
	 * <br><br>
	 * See further explanation at {@link BleDeviceConfig#manageLastDisconnectOnDisk}.
	 */
	public State.ChangeIntent getLastDisconnectIntent()
	{
		boolean hitDisk = BleDeviceConfig.bool(conf_device().manageLastDisconnectOnDisk, conf_mngr().manageLastDisconnectOnDisk);
		State.ChangeIntent lastDisconnect = m_mngr.m_lastDisconnectMngr.load(getMacAddress(), hitDisk);
		
		return lastDisconnect;
	}
	
	/**
	 * Set a listener here to be notified whenever this device's state changes.
	 */
	public void setListener_State(StateListener listener)
	{
		m_stateTracker.setListener(listener);
	}
	
	/**
	 * Set a listener here to be notified whenever a connection fails and to have control over retry behavior.
	 */
	public void setListener_ConnectionFail(ConnectionFailListener listener)
	{
		m_connectionFailMngr.setListener(listener);
	}
	
	/**
	 * Returns the connection failure retry count during a retry loop. Basic example use case is to provide a
	 * callback to {@link #setListener_ConnectionFail(ConnectionFailListener)} and update your application's UI with this method's
	 * return value downstream of your {@link ConnectionFailListener#onConnectionFail(BleDevice.ConnectionFailListener.Info))} override.
	 */
	public int getConnectionRetryCount()
	{
		return m_connectionFailMngr.getRetryCount();
	}
	
	/**
	 * Returns the bitwise state mask representation of {@link BleDeviceState} for this device.
	 * 
	 * @see BleDeviceState
	 */
	public int getStateMask()
	{
		return m_stateTracker.getState();
	}
	
	/**
	 * See similar explanation for {@link #getAverageWriteTime()}.
	 *  
	 * @see #getAverageWriteTime()
	 * @see BleManagerConfig#nForAverageRunningReadTime
	 */
	public double getAverageReadTime()
	{
		return m_readTimeEstimator != null ? m_readTimeEstimator.getRunningAverage() : 0.0;
	}
	
	/**
	 * Returns the average round trip time in seconds for all write operations started with
	 * {@link #write(UUID, byte[])} or {@link #write(UUID, byte[], ReadWriteListener)}.
	 * This is a running average with N being defined by {@link BleManagerConfig#nForAverageRunningWriteTime}.
	 * This may be useful for estimating how long a series of reads and/or writes will take. For example
	 * for displaying the estimated time remaining for a firmware update.
	 */
	public double getAverageWriteTime()
	{
		return m_writeTimeEstimator != null ? m_writeTimeEstimator.getRunningAverage() : 0.0;
	}
	
	/**
	 * Returns the raw RSSI retrieved from when the device was discovered or rediscovered.
	 * This value will also be updated when you call {@link #readRssi()} or {@link #startRssiPoll(Interval)}.
	 */
	public int getRssi()
	{
		return m_rssi;
	}
	
	/**
	 * Returns the approximate distance in meters based on {@link #getRssi()} and {@link #getTxPower()}.
	 * The higher the distance, the less the accuracy.
	 */
	public Distance getDistance()
	{
		return Distance.meters(Utils.calcDistance(getTxPower(), getRssi()));
	}
	
	/**
	 * Returns the calibrated transmitter power of the device. If this can't be figured out from the device itself
	 * then it backs up to the value provided in {@link BleDeviceConfig#defaultTxPower} or {@link BleManagerConfig#defaultTxPower}.
	 * 
	 * @see BleDeviceConfig#defaultTxPower;
	 */
	public int getTxPower()
	{
		if( m_knownTxPower != null )
		{
			return m_knownTxPower;
		}
		else
		{
			final Integer defaultTxPower = BleDeviceConfig.integer(conf_device().defaultTxPower, conf_mngr().defaultTxPower);
			final int toReturn = defaultTxPower == null ? BleDeviceConfig.DEFAULT_TX_POWER : defaultTxPower;
			
			return toReturn;
		}
	}
	
	/**
	 * Returns the scan record from when we discovered the device.
	 * May be empty but never null.
	 */
	public byte[] getScanRecord()
	{
		return m_scanRecord;
	}
	
	/**
	 * Returns the advertised services, if any, parsed from {@link #getScanRecord()}.
	 * May be empty but never null.
	 */
	public UUID[] getAdvertisedServices()
	{
		UUID[] toReturn = m_advertisedServices.size() > 0 ? new UUID[m_advertisedServices.size()] : EMPTY_UUID_ARRAY;
		return m_advertisedServices.toArray(toReturn);
	}
	
	/**
	 * Returns whether the device is in any of the provided states.
	 * 
	 * @see #is(BleDeviceState)
	 */
	public boolean isAny(BleDeviceState ... states)
	{
		for( int i = 0; i < states.length; i++ )
		{
			if( is(states[i]) )  return true;
		}
		
		return false;
	}
	
	/**
	 * Returns whether the device is in the provided state.
	 * 
	 * @see #isAny(BleDeviceState...)
	 */
	public boolean is(BleDeviceState state)
	{
		return state.overlaps(getStateMask());
	}
	
	/**
	 * Similar to {@link #is(BleDeviceState)} and {@link #isAny(BleDeviceState...)} but allows you
	 * to give a simple query made up of {@link BleDeviceState} and {@link Boolean} pairs. So an example
	 * would be <code>myDevice.is({@link BleDeviceState#CONNECTING}, true, {@link BleDeviceState#ATTEMPTING_RECONNECT}, false)</code>.
	 */
	public boolean is(Object ... query)
	{
		if( query == null || query.length == 0 )	return false;
		
		for( int i = 0; i < query.length; i+=2 )
		{
			Object first = query[i];
			Object second = i+1 < query.length ? query[i+1] : null;
			
			if( first == null || second == null )	return false;
			
			if( !(first instanceof BleDeviceState) || !(second instanceof Boolean) )
			{
				return false;
			}
			
			BleDeviceState state = (BleDeviceState) first;
			Boolean value  = (Boolean) second;
			
			if( value && !this.is(state) )			return false;
			else if( !value && this.is(state) )		return false;
		}
		
		return true;
	}
	
	/**
	 * If {@link #is(BleDeviceState)} returns true for the given state (i.e. if the device is in the given state)
	 * then this method will (a) return the amount of time that the device has been in the state. Otherwise, this will
	 * (b) return the amount of time that the device was *previously* in that state. Otherwise, if the device has never
	 * been in the state, it will (c) return 0.0 seconds. Case (b) might be useful for example for checking how long
	 * you <i>were</i> connected for after becoming {@link BleDeviceState#DISCONNECTED}, for analytics purposes or whatever.
	 */
	public Interval getTimeInState(BleDeviceState state)
	{
		return Interval.millis(m_stateTracker.getTimeInState(state.ordinal()));
	}
	
	/**
	 * Returns the raw, unmodified device name retrieved from the stack.
	 * Equivalent to {@link BluetoothDevice#getName()}. It's suggested to use {@link #getName_normalized()}
	 * if you're using the name to match/filter against something, e.g. an entry in a config file or for advertising filtering.
	 */
	public String getName_native()
	{
		return m_nativeWrapper.getNativeName();
	}
	
	/**
	 * The name retrieved from {@link #getName_native()} can change arbitrarily, like the last 4 of the MAC
	 * address can get appended sometimes, and spaces might get changed to underscores or vice-versa,
	 * caps to lowercase, etc. This may somehow be standard, to-the-spec behavior but to the newcomer it's
	 * confusing and potentially time-bomb-bug-inducing, like if you're using device name as a filter for something and 
	 * everything's working until one day your app is suddenly broken and you don't know why. This method is an
	 * attempt to normalize name behavior and always return the same name regardless of the underlying stack's
	 * whimsy. The target format is all lowercase and underscore-delimited with no trailing MAC address.
	 */
	public String getName_normalized()
	{
		return m_nativeWrapper.getNormalizedName();
	}
	
	/**
	 * Returns a name useful for logging and debugging. As of this writing it is {@link #getName_normalized()}
	 * plus the last four digits of the device's MAC address from {@link #getMacAddress()}.
	 * {@link BleDevice#toString()} uses this.
	 */
	public String getName_debug()
	{
		return m_nativeWrapper.getDebugName();
	}
	
	/**
	 * Provides just-in-case lower-level access to the native device instance. Be careful with this.
	 * It generally should not be needed. Only invoke "mutators" of this object in times of extreme need.
	 * If you are forced to use this please contact library developers to discuss possible feature addition
	 * or report bugs.
	 */
	public BluetoothDevice getNative()
	{
		return m_nativeWrapper.getDevice();
	}
	
	/**
	 * Returns the native characteristic for the given UUID in case you need lower-level access.
	 * You should only call this after {@link BleDeviceState#GETTING_SERVICES} has completed.
	 * Please see the warning for {@link #getNative()}.
	 */
	public BluetoothGattCharacteristic getNativeCharacteristic(UUID uuid)
	{
		P_Characteristic characteristic = m_serviceMngr.getCharacteristic(uuid);
		
		if (characteristic == null )  return null;
		
		return characteristic.getGuaranteedNative();
	}
	
	/**
	 * Returns the native service for the given UUID in case you need lower-level access.
	 * You should only call this after {@link BleDeviceState#GETTING_SERVICES} has completed.
	 * Please see the warning for {@link #getNative()}.
	 */
	public BluetoothGattService getNativeService(UUID uuid)
	{
		P_Service service = m_serviceMngr.get(uuid);
		
		if( service == null )  return null;
		
		return service.getNative();
	}
	
	/**
	 * See pertinent warning for {@link #getNative()}.
	 */
	public BluetoothGatt getNativeGatt()
	{
		return m_nativeWrapper.getGatt();
	}
	
	/**
	 * Returns this devices's manager.
	 */
	public BleManager getManager()
	{
		return m_mngr;
	}
	
	/**
	 * Returns the MAC address of this device, as retrieved from the native stack.
	 */
	public String getMacAddress()
	{
		return m_nativeWrapper.getAddress();
	}
	
	/**
	 * Attempts to create a bond. Analogous to {@link BluetoothDevice#createBond()}
	 * This is also sometimes called pairing, but while pairing and bonding are closely
	 * related, they are technically different from each other.
	 * 
	 * Bonding is required for reading/writing encrypted characteristics and, anecdotally,
	 * may improve connection stability. This is mentioned here and there on Internet
	 * threads complaining about Android BLE so take it with a grain of salt.
	 * 
	 * @see #unbond()
	 */
	public void bond()
	{
		if( isAny(BONDING, BONDED) )  return;
	
		m_queue.add(new P_Task_Bond(this, /*explicit=*/true, /*partOfConnection=*/false, m_taskStateListener));
		
		m_stateTracker.append(BONDING, E_Intent.EXPLICIT);
	}
	
	/**
	 * Opposite of {@link #bond()}.
	 * 
	 * @see #bond()
	 */
	public void unbond()
	{
		unbond_private(null);
	}
	
	/**
	 * Starts a connection process, or does nothing if already {@link BleDeviceState#CONNECTED} or {@link BleDeviceState#CONNECTING}.
	 * Use {@link #setListener_ConnectionFail(ConnectionFailListener)} and {@link #setListener_State(StateListener)} to receive callbacks for progress and errors.
	 */
	public void connect()
	{
		connect((StateListener)null);
	}
	
	/**
	 * Same as {@link #connect()} but calls {@link #setListener_State(StateListener)} for you.
	 */
	public void connect(StateListener stateListener)
	{
		connect(stateListener, null);
	}
	
	/**
	 * Same as {@link #connect()} but calls {@link #setListener_State(StateListener)} and
	 * {@link #setListener_ConnectionFail(ConnectionFailListener)} for you.
	 */
	public void connect(StateListener stateListener, ConnectionFailListener failListener)
	{
		connect(null, null, stateListener, failListener);
	}
	
	/**
	 * Same as {@link #connect()} but provides a hook for the app to do some kind of authentication
	 * handshake if it wishes. This is popular with commercial BLE devices where you don't want 
	 * hobbyists or competitors using your devices for nefarious purposes - like releasing a better
	 * application for your device than you ;-). Usually the characteristics read/written inside
	 * this transaction are encrypted and so one way or another will require the device to become
	 * {@link BleDeviceState#BONDED}. This should happen automatically for you, i.e you shouldn't
	 * need to call {@link #bond()} yourself.
	 * 
	 * @see #connect()
	 * @see BleDeviceState#AUTHENTICATING
	 * @see BleDeviceState#AUTHENTICATED
	 */
	public void connect(BleTransaction.Auth authenticationTxn)
	{
		connect(authenticationTxn, (StateListener)null);
	}
	
	/**
	 * Same as {@link #connect(BleTransaction.Auth)} but calls {@link #setListener_State(StateListener)} for you.
	 */
	public void connect(BleTransaction.Auth authenticationTxn, StateListener stateListener)
	{
		connect(authenticationTxn, stateListener, (ConnectionFailListener) null);
	}
	
	/**
	 * Same as {@link #connect(BleTransaction.Auth)} but calls {@link #setListener_State(StateListener)}
	 * and {@link #setListener_ConnectionFail(ConnectionFailListener)} for you.
	 */
	public void connect(BleTransaction.Auth authenticationTxn, StateListener stateListener, ConnectionFailListener failListener)
	{
		connect(authenticationTxn, null, stateListener, failListener);
	}
	
	/**
	 * Same as {@link #connect()} but provides a hook for the app to do some kind of initialization
	 * before it's considered fully {@link BleDeviceState#INITIALIZED}. For example if you had a BLE-enabled thermometer
	 * you could use this transaction to attempt an initial temperature read before updating your UI
	 * to indicate "full" connection success, even though BLE connection itself already succeeded.
	 * 
	 * @see #connect()
	 * @see BleDeviceState#INITIALIZING
	 * @see BleDeviceState#INITIALIZED
	 */
	public void connect(BleTransaction.Init initTxn)
	{
		connect(initTxn, (StateListener)null);
	}
	
	/**
	 * Same as {@link #connect(BleTransaction.Init)} but calls {@link #setListener_State(StateListener)} for you.
	 */
	public void connect(BleTransaction.Init initTxn, StateListener stateListener)
	{
		connect(initTxn, stateListener, (ConnectionFailListener)null);
	}
	
	
	/**
	 * Same as {@link #connect(BleTransaction.Init)} but calls 
	 * {@link #setListener_State(StateListener)} and {@link #setListener_ConnectionFail(ConnectionFailListener)} for you.
	 */
	public void connect(BleTransaction.Init initTxn, StateListener stateListener, ConnectionFailListener failListener)
	{
		connect(null, initTxn, stateListener, failListener);
	}
	
	/**
	 * Combination of {@link #connect(BleTransaction.Auth)} and {@link #connect(BleTransaction.Init)}.
	 * See those two methods for explanation.
	 * 
	 * @see #connect()
	 * @see #connect(BleTransaction.Auth)
	 * @see #connect(BleTransaction.Init)
	 */
	public void connect(BleTransaction.Auth authenticationTxn, BleTransaction.Init initTxn)
	{
		connect(authenticationTxn, initTxn, (StateListener)null, (ConnectionFailListener)null);
	}
	
	/**
	 * Same as {@link #connect(BleTransaction, BleTransaction)} but calls {@link #setListener_State(StateListener)} for you.
	 */
	public void connect(BleTransaction.Auth authenticationTxn, BleTransaction.Init initTxn, StateListener stateListener)
	{
		connect(authenticationTxn, initTxn, stateListener, (ConnectionFailListener)null);
	}
	
	/**
	 * Same as {@link #connect(BleTransaction.Auth, BleTransaction.Init)} but calls
	 * {@link #setListener_State(StateListener)} and {@link #setListener_ConnectionFail(ConnectionFailListener)} for you.
	 */
	public void connect(BleTransaction.Auth authenticationTxn, BleTransaction.Init initTxn, StateListener stateListener, ConnectionFailListener failListener)
	{
		if( stateListener != null )
		{
			setListener_State(stateListener);
		}
		
		if( failListener != null )
		{
			setListener_ConnectionFail(failListener);
		}
		
		m_connectionFailMngr.onExplicitConnectionStarted();
		
		connect_private(authenticationTxn, initTxn, /*isReconnect=*/false);
	}
	
	/**
	 * Disconnects from a connected device or does nothing if already {@link BleDeviceState#DISCONNECTED}.
	 * You can call this at any point during the connection process as a whole, during
	 * reads and writes, during transactions, whenever, and the device will cleanly
	 * cancel all ongoing operations. This method will also bring the device out of the
	 * {@link BleDeviceState#ATTEMPTING_RECONNECT} state.
	 * 
	 * @see ConnectionFailListener.Reason#EXPLICIT_DISCONNECT
	 */
	public void disconnect()
	{
		disconnectWithReason(/*priority=*/null, Reason.EXPLICIT_DISCONNECT, BleDeviceConfig.GATT_STATUS_NOT_APPLICABLE);
	}
	
	/**
	 * Convenience method that calls {@link BleManager#undiscover(BleDevice)}.
	 * 
	 * @see BleManager#undiscover(BleDevice)
	 */
	public boolean undiscover()
	{
		return m_mngr.undiscover(this);
	}
	
	/**
	 * First checks referential equality and if {@link Boolean#FALSE} checks equality of {@link #getMacAddress()}.
	 * Note that ideally this method isn't useful to you and never returns true (besides the identity
	 * case, which isn't useful to you). Otherwise it probably means your app is holding on to old references
	 * that have been undiscovered, and this may be a bug or bad design decision in your code. This library
	 * will (well, should) never hold references to two devices such that this method returns true for them.
	 * 
	 * @see BleManager.DiscoveryListener_Full#onDeviceUndiscovered(BleDevice)
	 */
	public boolean equals(BleDevice device)
	{
		if( device == null )  return false;
	
		if( device == this )  return true;
		
		if( device.getNative() == null || this.getNative() == null )  return false;
		
		return device.getNative().equals(this.getNative());
	}

	/**
	 * Returns {@link #equals(BleDevice)} if object is an instance of {@link BleDevice}.
	 * Otherwise calls super.
	 * 
	 * @see BleDevice#equals(BleDevice)
	 */
	@Override public boolean equals(Object object)
	{
		if( object instanceof BleDevice )
		{
			BleDevice object_cast = (BleDevice) object;
			
			return this.equals(object_cast);
		}
		
		return false;
	}
	
	/**
	 * Starts a periodic read of a particular characteristic. Use this wherever you can in place of {@link #enableNotify(UUID, ReadWriteListener)}.
	 * One use case would be to periodically read wind speed from a weather device. You *could* develop your device firmware to send
	 * notifications to the app only when the wind speed changes, but Android has observed stability issues with notifications,
	 * so use them only when needed.
	 * 
	 * @see #startChangeTrackingPoll(UUID, Interval, ReadWriteListener)
	 * @see #enableNotify(UUID, ReadWriteListener)
	 * @see #stopPoll(UUID, ReadWriteListener)
	 */
	public void startPoll(UUID uuid, Interval interval, ReadWriteListener listener)
	{
		m_pollMngr.startPoll(uuid, interval.secs(), listener, /*trackChanges=*/false, /*usingNotify=*/false);
	}
	
	/**
	 * Similar to {@link #startPoll(UUID, Interval, ReadWriteListener)} but only invokes a
	 * callback when a change in the characteristic value is detected.
	 * Use this in preference to {@link #enableNotify(UUID, ReadWriteListener)()} if possible.
	 */
	public void startChangeTrackingPoll(UUID uuid, Interval interval, ReadWriteListener listener)
	{
		m_pollMngr.startPoll(uuid, interval.secs(), listener, /*trackChanges=*/true, /*usingNotify=*/false);
	}
	
	/**
	 * Stops a poll(s) started by either {@link #startPoll(UUID, Interval, ReadWriteListener)} or
	 * {@link #startChangeTrackingPoll(UUID, Interval, ReadWriteListener)}.
	 * This will stop all polls matching the provided parameters.
	 * 
	 * @see #startPoll(UUID, Interval, ReadWriteListener)
	 * @see #startChangeTrackingPoll(UUID, Interval, ReadWriteListener)
	 */
	public void stopPoll(UUID uuid, ReadWriteListener listener)
	{
		stopPoll_private(uuid, null, listener);
	}
	
	/**
	 * Same as {@link #stopPoll(UUID, ReadWriteListener)} but with added filtering for the poll {@link Interval}.
	 */
	public void stopPoll(UUID uuid, Interval interval, ReadWriteListener listener)
	{
		stopPoll_private(uuid, interval.secs(), listener);
	}
	
	/**
	 * Writes to the device without a callback.
	 * 
	 * @see #write(UUID, byte[], ReadWriteListener)
	 */
	public void write(UUID uuid, byte[] data)
	{
		this.write(uuid, data, (ReadWriteListener)null);
	}
	
	/**
	 * Writes to the device with a callback.
	 * 
	 * @see #write(UUID, byte[])
	 */
	public void write(UUID uuid, byte[] data, ReadWriteListener listener)
	{
		write_internal(uuid, data, new P_WrappingReadWriteListener(listener, m_mngr.m_mainThreadHandler, m_mngr.m_config.postCallbacksToMainThread));
	}
	
	/**
	 * Same as {@link #readRssi(ReadWriteListener)} but use this method when you don't much care when/if the RSSI
	 * is actually updated.
	 */
	public void readRssi()
	{
		readRssi(null);
	}
	
	/**
	 * Wrapper for {@link BluetoothGatt#readRemoteRssi()}. This will eventually update the value returned
	 * by {@link #getRssi()} but it is not instantaneous. When a new RSSI is actually received the given
	 * listener will be called. The device must be {@link BleDeviceState#CONNECTED} for this call to succeed.
	 * When the device is not {@link BleDeviceState#CONNECTED} then the value returned by {@link #getRssi()}
	 * will be automatically updated every time this device is discovered (or rediscovered) by a scan operation.
	 */
	public void readRssi(ReadWriteListener listener)
	{
		Result earlyOut = m_serviceMngr.getEarlyOutResult(Uuids.INVALID, EMPTY_BYTE_ARRAY, Type.READ);
		
		if( earlyOut != null )
		{
			if( listener != null )
			{
				listener.onResult(earlyOut);
			}
			
			return;
		}
		
		P_WrappingReadWriteListener wrappingListener = listener != null ? new P_WrappingReadWriteListener(listener, m_mngr.m_mainThreadHandler, m_mngr.m_config.postCallbacksToMainThread) : null;
		readRssi_internal(Type.READ, wrappingListener);
	}
	
	/**
	 * Same as {@link #startPoll(UUID, Interval, ReadWriteListener)} but for when you don't care when/if the RSSI
	 * is actually updated.
	 */
	public void startRssiPoll(Interval interval)
	{
		startRssiPoll(interval, null);
	}
	
	/**
	 * Kicks off a poll that automatically calls {@link #readRssi(ReadWriteListener)} at the {@link Interval} frequency specified.
	 * This can be called before the device is actually {@link BleDeviceState#CONNECTED}. If you call this more than once in a row
	 * then the most recent call's parameters will be respected.
	 */
	public void startRssiPoll(Interval interval, ReadWriteListener listener)
	{
		m_rssiPollMngr.start(interval.secs(), listener);
	}
	
	/**
	 * Stops an RSSI poll previously started either by {@link #startRssiPoll(Interval)} or {@link #startRssiPoll(Interval, ReadWriteListener)}.
	 */
	public void stopRssiPoll()
	{
		m_rssiPollMngr.stop();
	}
	
	void readRssi_internal(Type type, P_WrappingReadWriteListener listener)
	{
		m_queue.add(new P_Task_ReadRssi(this, listener, m_txnMngr.getCurrent(), getOverrideReadWritePriority(), type));
	}
	
	/**
	 * Reads a characteristic from the device.
	 */
	public void read(UUID uuid, ReadWriteListener listener)
	{
		read_internal(uuid, Type.READ, new P_WrappingReadWriteListener(listener, m_mngr.m_mainThreadHandler, m_mngr.m_config.postCallbacksToMainThread));
	}
	
	/**
	 * Enables notification on the given characteristic. The listener will be called both for the notifications
	 * themselves and for the actual registration for the notification. <code>switch</switch> on {@link Type#ENABLING_NOTIFICATION}
	 * and {@link Type#NOTIFICATION} (or {@link Type#INDICATION}) in your listener to distinguish between these.
	 */
	public void enableNotify(UUID uuid, ReadWriteListener listener)
	{
		this.enableNotify(uuid, Interval.INFINITE, listener);
	}
	
	/**
	 * Same as {@link #enableNotify(UUID, ReadWriteListener)} but forces a read after a given amount of time.
	 * If you received {@link Status#SUCCESS} for {@link Type#ENABLING_NOTIFICATION} but haven't received an
	 * actual notification in some time it may be a sign that notifications have broken in the underlying stack.
	 */
	public void enableNotify(UUID uuid, Interval forceReadTimeout, ReadWriteListener listener)
	{
		Result earlyOutResult = m_serviceMngr.getEarlyOutResult(uuid, EMPTY_BYTE_ARRAY, Type.ENABLING_NOTIFICATION);
		
		if( earlyOutResult != null )
		{
			if( listener != null )
			{
				listener.onResult(earlyOutResult);
			}
			
			if( earlyOutResult.status() == Status.NO_MATCHING_TARGET || (Interval.INFINITE.equals(forceReadTimeout) || Interval.DISABLED.equals(forceReadTimeout)) )
			{
				//--- DRK > No need to put this notify in the poll manager because either the characteristic wasn't found
				//---		or the notify (or indicate) property isn't supported and we're not doing a backing read poll. 		
				return;
			}
		}
		
		P_Characteristic characteristic = m_serviceMngr.getCharacteristic(uuid);
		E_NotifyState notifyState = m_pollMngr.getNotifyState(uuid);
		boolean shouldSendOutNotifyEnable = notifyState == E_NotifyState.NOT_ENABLED && (earlyOutResult == null || earlyOutResult.status() != Status.OPERATION_NOT_SUPPORTED);
		
		if( shouldSendOutNotifyEnable && characteristic != null && is(CONNECTED) )
		{
			P_WrappingReadWriteListener wrappingListener = new P_WrappingReadWriteListener(listener, m_mngr.m_mainThreadHandler, m_mngr.m_config.postCallbacksToMainThread);
			m_queue.add(new P_Task_ToggleNotify(characteristic, /*enable=*/true, wrappingListener));
			
			m_pollMngr.onNotifyStateChange(uuid, E_NotifyState.ENABLING);
		}
		else if( notifyState == E_NotifyState.ENABLED )
		{
			if( listener != null )
			{
				Result result = m_pollMngr.newAlreadyEnabledResult(characteristic);
				listener.onResult(result);
			}
		}
		
		m_pollMngr.startPoll(uuid, forceReadTimeout.secs(), listener, /*trackChanges=*/true, /*usingNotify=*/true);
	}	
	
	/**
	 * Disables all notifications enabled by {@link #enableNotify(UUID, ReadWriteListener)} or 
	 * {@link #enableNotify(UUID, Interval, ReadWriteListener)}. The listener provided should be
	 * the same one that you passed to {@link #enableNotify(UUID, ReadWriteListener)}. Listen
	 * for {@link Type#DISABLING_NOTIFICATION} in your listener to know when the remote device actually confirmed.
	 */
	public void disableNotify(UUID uuid, ReadWriteListener listener)
	{
		this.disableNotify_private(uuid, null, listener);
	}
	
	/**
	 * Same as {@link #disableNotify(UUID, ReadWriteListener)} but filters on the given {@link Interval}.
	 */
	public void disableNotify(UUID uuid, Interval forceReadTimeout, ReadWriteListener listener)
	{
		this.disableNotify_private(uuid, forceReadTimeout.secs(), listener);
	}
	
	/**
	 * Kicks off an OTA transaction if it's not already taking place and the device is {@link BleDeviceState#INITIALIZED}.
	 * This will put the device into the {@link BleDeviceState#PERFORMING_OTA} state.
	 * <br><br>
	 * TIP: Use the {@link TimeEstimator} class to let your users know roughly how much time it will take for the ota to complete.
	 * 
	 * @return	{@link Boolean#TRUE} if firmware update has started, otherwise {@link Boolean#FALSE} if device is either already
	 * 			{@link BleDeviceState#PERFORMING_OTA} or is not {@link BleDeviceState#INITIALIZED}.
	 * 	
	 * @see BleManagerConfig#includeOtaReadWriteTimesInAverage
	 * @see BleManagerConfig#autoScanDuringOta
	 */
	public boolean performOta(BleTransaction.Ota txn)
	{
		if( is(PERFORMING_OTA) )  return false;
		if( !is(INITIALIZED) )  return false;
		
		m_txnMngr.onOta(txn);
		
		return true;
	}
	
	Interval getTaskTimeout(final BleTask task)
	{
		return BleDeviceConfig.getTimeout(task, conf_device(), conf_mngr());
	}
	
	/**
	 * Returns the device's name and current state for logging and debugging purposes.
	 */
	@Override public String toString()
	{
		return getName_debug() + " " + m_stateTracker.toString();
	}
	
	private boolean shouldAddOperationTime()
	{
		boolean includeFirmwareUpdateReadWriteTimesInAverage = BleDeviceConfig.bool(conf_device().includeOtaReadWriteTimesInAverage, conf_mngr().includeOtaReadWriteTimesInAverage);
		
		return includeFirmwareUpdateReadWriteTimesInAverage || !is(PERFORMING_OTA);
	}
	
	void addReadTime(double timeStep)
	{
		if( !shouldAddOperationTime() )  return;
		
		if( m_readTimeEstimator != null )
		{
			m_readTimeEstimator.addTime(timeStep);
		}
	}
	
	void addWriteTime(double timeStep)
	{
		if( !shouldAddOperationTime() )  return;
		
		if( m_writeTimeEstimator != null )
		{
			m_writeTimeEstimator.addTime(timeStep);
		}
	}
	
	void setToAlwaysUseAutoConnectIfItWorked()
	{
		m_alwaysUseAutoConnect = m_useAutoConnect;
	}
	
	boolean shouldUseAutoConnect()
	{
		return m_useAutoConnect;
	}
	
	P_BleDevice_Listeners getListeners()
	{
		return m_listeners;
	}
	
	P_TaskQueue getTaskQueue(){						return m_queue;								}
	PA_StateTracker getStateTracker(){				return m_stateTracker;						}
	BleTransaction getFirmwareUpdateTxn(){			return m_txnMngr.m_firmwareUpdateTxn;		}
	P_PollManager getPollManager(){					return m_pollMngr;							}
	P_ServiceManager getServiceManager(){			return m_serviceMngr;						}
	
	void onNewlyDiscovered(List<UUID> advertisedServices_nullable, int rssi, byte[] scanRecord_nullable)
	{
		clear_discovery();
		
		onDiscovered_private(advertisedServices_nullable, rssi, scanRecord_nullable);

		m_stateTracker.update
		(
			E_Intent.IMPLICIT,
			BONDING,		m_nativeWrapper.isNativelyBonding(),
			BONDED,			m_nativeWrapper.isNativelyBonded(),
			UNBONDED,		m_nativeWrapper.isNativelyUnbonded(),
			UNDISCOVERED,	false,
			DISCOVERED,		true,
			ADVERTISING,	true,
			DISCONNECTED,	true
		);
	}
	
	void onRediscovered(List<UUID> advertisedServices_nullable, int rssi, byte[] scanRecord_nullable)
	{
		onDiscovered_private(advertisedServices_nullable, rssi, scanRecord_nullable);
		
		m_stateTracker.update
		(
			PA_StateTracker.E_Intent.IMPLICIT,
			BONDING,		m_nativeWrapper.isNativelyBonding(),
			BONDED,			m_nativeWrapper.isNativelyBonded()
		);
	}
	
	void onUndiscovered(E_Intent intent)
	{
		clear_undiscovery();
		
		m_reconnectMngr.stop();
		
		m_stateTracker.set
		(
			intent,
			UNDISCOVERED,	true,
			DISCOVERED,		false,
			ADVERTISING,	false,
			BONDING,		m_nativeWrapper.isNativelyBonding(),
			BONDED,			m_nativeWrapper.isNativelyBonded(),
			UNBONDED,		m_nativeWrapper.isNativelyUnbonded()
		);
	}
	
	double getTimeSinceLastDiscovery()
	{
		return m_timeSinceLastDiscovery;
	}
	
	private void onDiscovered_private(List<UUID> advertisedServices_nullable, int rssi, byte[] scanRecord_nullable)
	{
		m_timeSinceLastDiscovery = 0.0;
		updateRssi(rssi);
		m_advertisedServices = advertisedServices_nullable == null || advertisedServices_nullable.size() == 0 ? m_advertisedServices : advertisedServices_nullable;
		m_scanRecord = scanRecord_nullable != null ? scanRecord_nullable : m_scanRecord;
	}
	
	void updateRssi(int rssi)
	{
		m_rssi = rssi;
	}
	
	void update(double timeStep)
	{
		m_timeSinceLastDiscovery += timeStep;
		
		m_pollMngr.update(timeStep);
		m_txnMngr.update(timeStep);
		m_reconnectMngr.update(timeStep);
		m_rssiPollMngr.update(timeStep);
	}
	
	void unbond_private(PE_TaskPriority priority)
	{
		m_queue.add(new P_Task_Unbond(this, m_taskStateListener, priority));
		
		m_stateTracker.update(E_Intent.EXPLICIT, BONDED, false, BONDING, false, UNBONDED, true);
	}
	
	void onBondTaskStateChange(PA_Task task, PE_TaskState state)
	{
		E_Intent intent = task.isExplicit() ? E_Intent.EXPLICIT : E_Intent.IMPLICIT;
		
		if( task.getClass() == P_Task_Bond.class )
		{
			if( state.isEndingState() )
			{
				if( state == PE_TaskState.SUCCEEDED )
				{
					this.onNativeBond(intent);
				}
				else
				{
					this.onNativeBondFailed(intent);
				}
			}
		}
		else if( task.getClass() == P_Task_Unbond.class )
		{
			if( state == PE_TaskState.SUCCEEDED )
			{
				this.onNativeUnbond(intent);
			}
			else
			{
				// not sure what to do here, if anything
			}
		}
	}
	
	void onNativeUnbond(E_Intent intent)
	{
		m_stateTracker.update(intent, BONDED, false, BONDING, false, UNBONDED, true);
	}
	
	void onNativeBonding(E_Intent intent)
	{
		m_stateTracker.update(intent, BONDED, false, BONDING, true, UNBONDED, false);
	}
	
	void onNativeBond(E_Intent intent)
	{
		m_stateTracker.update(intent, BONDED, true, BONDING, false, UNBONDED, false);
	}
	
	void onNativeBondFailed(E_Intent intent)
	{
		m_stateTracker.update(intent, BONDED, false, BONDING, false, UNBONDED, true);
	}
	
	void attemptReconnect()
	{
		connect_private(m_txnMngr.m_authTxn, m_txnMngr.m_initTxn, /*isReconnect=*/true);
	}
	
	private void connect_private(BleTransaction.Auth authenticationTxn, BleTransaction.Init initTxn, boolean isReconnect)
	{
		m_lastConnectOrDisconnectWasUserExplicit = true;
		
		if( isAny(CONNECTED, CONNECTING, CONNECTING_OVERALL))  return;
		
		if( is(INITIALIZED) )
		{
			m_mngr.ASSERT(false, "Device is initialized but not connected!");
			
			return;
		}
	
		m_txnMngr.onConnect(authenticationTxn, initTxn);
		
		m_queue.add(new P_Task_Connect(this, m_taskStateListener));
		
		onConnecting(/*definitelyExplicit=*/true, isReconnect);
	}
	
	void onConnecting(boolean definitelyExplicit, boolean isReconnect)
	{
		m_lastConnectOrDisconnectWasUserExplicit = definitelyExplicit;
		
		if( is(/*already*/CONNECTING) )
		{
			P_Task_Connect task = getTaskQueue().getCurrent(P_Task_Connect.class, this);
			boolean mostDefinitelyExplicit = task != null && task.isExplicit();
			
			//--- DRK > Not positive about this assert...we'll see if it trips.
			m_mngr.ASSERT(definitelyExplicit || mostDefinitelyExplicit);
		}
		else
		{
			if( definitelyExplicit && !isReconnect )
			{
				//--- DRK > We're stopping the reconnect process (if it's running) because the user has decided to explicitly connect
				//---		for whatever reason. Making a judgement call that the user would then expect reconnect to stop.
				//---		In other words it's not stopped for any hard technical reasons...it could go on.
				m_reconnectMngr.stop();
				m_stateTracker.update(E_Intent.EXPLICIT, ATTEMPTING_RECONNECT, false, CONNECTING, true, CONNECTING_OVERALL, true, DISCONNECTED, false, ADVERTISING, false);
			}
			else
			{
				m_stateTracker.update(lastConnectDisconnectIntent(), CONNECTING, true, CONNECTING_OVERALL, true, DISCONNECTED, false, ADVERTISING, false);
			}
		}
	}
	
	private boolean isBondingOrBonded()
	{
		//--- DRK > These asserts are here because, as far as I could discern from logs, the abstracted
		//---		state for bonding/bonded was true, but when we did an encrypted write, it kicked
		//---		off a bonding operation, implying that natively the bonding state silently changed
		//---		since we discovered the device. I really don't know.
		//---		UPDATE: Nevermind, the reason bonding wasn't happening after connection was because
		//---				it was using the default config option of false. Leaving asserts here anywway
		//---				cause they can't hurt.
		//---		UPDATE AGAIN: Actually, these asserts can hit if you're connected to a device, you go
		//---		into OS settings, unbond, which kicks off an implicit disconnect which then kicks off
		//---		an implicit reconnect...race condition makes it so that you can query the bond state
		//---		and get its updated value before the bond state callback gets sent
		//---		UPDATE AGAIN AGAIN: Nevermind, it seems getBondState *can* actually lie, so original comment sorta stands...wow.
//		m_mngr.ASSERT(m_stateTracker.checkBitMatch(BONDED, isNativelyBonded()));
//		m_mngr.ASSERT(m_stateTracker.checkBitMatch(BONDING, isNativelyBonding()));
		
		return m_nativeWrapper.isNativelyBonded() || m_nativeWrapper.isNativelyBonding();
	}
	
	void onNativeConnect(boolean explicit)
	{
		m_lastDisconnectWasBecauseOfBleTurnOff = false; // DRK > Just being anal.
		
		E_Intent intent = explicit && !is(ATTEMPTING_RECONNECT) ? E_Intent.EXPLICIT : E_Intent.IMPLICIT;
		m_lastConnectOrDisconnectWasUserExplicit = intent == E_Intent.EXPLICIT;
		
		if( is(/*already*/CONNECTED) )
		{
			//--- DRK > Possible to get here when implicit tasks are involved I think. Not sure if assertion should be here,
			//---		and if it should it perhaps should be keyed off whether the task is implicit or something.
			//---		Also possible to get here for example on connection fail retries, where we queue a disconnect
			//---		but that gets immediately soft-cancelled by what will be a redundant connect task.
			//---		OVERALL, This assert is here because I'm just curious how it hits (it does).
			String message = "nativelyConnected=" + m_logger.gattConn(m_nativeWrapper.getConnectionState()) + " gatt==" + m_nativeWrapper.getGatt();
//			m_mngr.ASSERT(false, message);
			m_mngr.ASSERT(m_nativeWrapper.isNativelyConnected(), message);
			
			return;
		}
		
		m_mngr.ASSERT(m_nativeWrapper.getGatt() != null);
		
		//--- DRK > There exists a fringe case like this: You try to connect with autoConnect==true in the gatt object.
		//---		The connection fails, so you stop trying. Then you turn off the remote device. Device gets "undiscovered".
		//---		You turn the device back on, and apparently underneath the hood, this whole time, the stack has been trying
		//---		to reconnect, and now it does, *without* (re)discovering the device first, or even discovering it at all.
		//---		So as usual, here's another gnarly workaround to ensure a consistent API experience through SweetBlue.
		//---
		//---		NOTE: We do explicitly disconnect after a connection failure if we're using autoConnect, so this
		//---				case shouldn't really come up much or at all with that in place.
		if( !m_mngr.hasDevice(getMacAddress()) )
		{
			m_mngr.onDiscovered_fromRogueAutoConnect(this, /*newlyDiscovered=*/true, m_advertisedServices, getScanRecord(), getRssi());
		}
		
		//--- DRK > Some trapdoor logic for bad android ble bug.
		int nativeBondState = m_nativeWrapper.getNativeBondState();
		if( nativeBondState == BluetoothDevice.BOND_BONDED )
		{
			//--- DRK > Trying to catch fringe condition here of stack lying to us about bonded state.
			//---		This is not about finding a logic error in my code.
			m_mngr.ASSERT(m_mngr.getNative().getAdapter().getBondedDevices().contains(m_nativeWrapper.getDevice()));
		}
		m_logger.d(m_logger.gattBondState(m_nativeWrapper.getNativeBondState()));
		
		
		//final boolean autobond = BleDeviceConfig.bool(conf_device().autoBondAfterConnect, conf_mngr().autoBondAfterConnect);
		final boolean bond = false;//autobond && !isBondingOrBonded();
		
		if( bond )
		{
			m_queue.add(new P_Task_Bond(this, /*explicit=*/true, /*partOfConnection=*/true, new PA_Task.I_StateListener()
			{
				@Override public void onStateChange(PA_Task task, PE_TaskState state)
				{
					if( state.isEndingState() )
					{
						if(state == PE_TaskState.SUCCEEDED )
						{
							if( m_mngr.m_config.autoGetServices )
							{
								getServices(BONDING, false, BONDED, true);
							}
							else
							{
								m_txnMngr.runAuthOrInitTxnIfNeeded(BONDING, false, BONDED, true);
							}
						}
						else if( state == PE_TaskState.SOFTLY_CANCELLED )
						{
							//--- DRK > This actually means native bonding succeeded but since it was "cancelled"
							//---		by a disconnect we don't update the state tracker.
//								onNativeBond();
						}
						else
						{
							if( m_mngr.m_config.autoGetServices )
							{
								getServices(BONDING, false, BONDED, false);
							}
							else
							{
								m_txnMngr.runAuthOrInitTxnIfNeeded(BONDING, false, BONDED, false);
							}
						}
					}
				}
			}));

			m_stateTracker.update
			(
				intent,  DISCONNECTED,false,  CONNECTING_OVERALL,true,  CONNECTING,false,  CONNECTED,true,  BONDING,true,  ADVERTISING,false
			);
		}
		else
		{
			boolean autoGetServices = BleDeviceConfig.bool(conf_device().autoGetServices, conf_mngr().autoGetServices);
			if( autoGetServices )
			{
				getServices(DISCONNECTED,false, CONNECTING_OVERALL,true, CONNECTING,false, CONNECTED,true, ADVERTISING,false);
			}
			else
			{
				m_txnMngr.runAuthOrInitTxnIfNeeded(DISCONNECTED,false, CONNECTING_OVERALL,true, CONNECTING,false, CONNECTED,true, ADVERTISING,false);
			}
		}
	}
	
	private void getServices(Object ... extraFlags)
	{
		if( !m_nativeWrapper.isNativelyConnected() )
		{
			//--- DRK > This accounts for certain fringe cases, for example the Nexus 5 log when you unbond a device from the OS settings while it's connected:
//			07-03 12:53:49.489: D/BluetoothGatt(11442): onClientConnectionState() - status=0 clientIf=5 device=D4:81:CA:00:1D:61
//			07-03 12:53:49.499: I/BleDevice_Listeners(11442): FAY(11538) onConnectionStateChange() - GATT_SUCCESS(0) STATE_DISCONNECTED(0)
//			07-03 12:53:49.759: I/BleManager_Listeners(11442): AMY(11442) onNativeBondStateChanged() - previous=BOND_BONDED(12) new=BOND_NONE(10)
//			07-03 12:53:54.299: D/BluetoothGatt(11442): onClientConnectionState() - status=0 clientIf=5 device=D4:81:CA:00:1D:61
//			07-03 12:53:54.299: I/BleDevice_Listeners(11442): CAM(11453) onConnectionStateChange() - GATT_SUCCESS(0) STATE_CONNECTED(2)
//			07-03 12:53:54.299: D/BleDevice(11442): CAM(11453) getNativeBondState() - BOND_NONE(10)
//			07-03 12:53:54.309: D/BleDevice(11442): CAM(11453) getNativeBondState() - BOND_NONE(10)
//			07-03 12:53:54.309: I/A_BtTask(11442): CAM(11453) setState() - BtTask_Bond(CREATED)
//			07-03 12:53:54.309: I/A_BtTask(11442): CAM(11453) setState() - BtTask_Bond(QUEUED) - 4032
//			07-03 12:53:54.309: I/BtTaskQueue(11442): CAM(11453) printQueue() - null [BtTask_Bond(QUEUED)]
//			07-03 12:53:54.309: D/BluetoothManager(11442): getConnectionState()
//			07-03 12:53:54.319: D/BluetoothManager(11442): getConnectedDevices
//			07-03 12:53:54.329: I/A_BtTask(11442): BEN(11488) setState() - BtTask_Bond(ARMED) - 4032
//			07-03 12:53:54.339: I/BtTaskQueue(11442): BEN(11488) printQueue() - BtTask_Bond(ARMED) [ empty ]
//			07-03 12:53:54.379: I/A_BtTask(11442): BEN(11488) setState() - BtTask_Bond(EXECUTING) - 4033
//			07-03 12:53:54.379: D/BleDevice(11442): GUS(11487) getNativeBondState() - BOND_NONE(10)
//			07-03 12:53:54.379: D/BleDevice(11442): GUS(11487) getNativeBondState() - BOND_NONE(10)
//			07-03 12:53:54.419: I/BleManager_Listeners(11442): AMY(11442) onNativeBondStateChanged() - previous=BOND_NONE(10) new=BOND_BONDING(11)
//			07-03 12:53:54.599: D/BluetoothGatt(11442): onClientConnectionState() - status=133 clientIf=5 device=D4:81:CA:00:1D:61
//			07-03 12:53:54.599: W/BleDevice_Listeners(11442): FAY(11538) onConnectionStateChange() - UNKNOWN_STATUS(133) STATE_DISCONNECTED(0)
//			07-03 12:53:54.599: I/BleManager_Listeners(11442): AMY(11442) onNativeBondStateChanged() - previous=BOND_BONDING(11) new=BOND_NONE(10)
//			07-03 12:53:54.599: I/A_BtTask(11442): AMY(11442) setState() - BtTask_Bond(FAILED) - 4042
//			07-03 12:53:54.609: I/A_BtTask(11442): AMY(11442) setState() - BtTask_DiscoverServices(CREATED)
//			07-03 12:53:54.609: I/A_BtTask(11442): AMY(11442) setState() - BtTask_DiscoverServices(QUEUED) - 4042
//			07-03 12:53:54.609: I/BtTaskQueue(11442): AMY(11442) printQueue() - null [BtTask_DiscoverServices(QUEUED)]

			return;
		}
		
		m_serviceMngr.clear();
		m_queue.add(new P_Task_DiscoverServices(this, m_taskStateListener));
		
		//--- DRK > We check up top, but check again here cause we might have been disconnected on another thread in the mean time.
		//---		Even without this check the library should still be in a goodish state. Might send some weird state
		//---		callbacks to the app but eventually things settle down and we're good again.
		if( m_nativeWrapper.isNativelyConnected() )
		{
			m_stateTracker.update(lastConnectDisconnectIntent(), extraFlags, GETTING_SERVICES, true);
		}
	}
	
	void onNativeConnectFail(PE_TaskState state, int gattStatus, AutoConnectUsage autoConnectUsage)
	{
		m_nativeWrapper.closeGattIfNeeded(/*disconnectAlso=*/true);
		
		if( state == PE_TaskState.SOFTLY_CANCELLED || state == PE_TaskState.NO_OP )  return;
	
		boolean attemptingReconnect = is(ATTEMPTING_RECONNECT);
		BleDeviceState highestState = BleDeviceState.getTransitoryConnectionState(getStateMask());
		
		
//		if( !m_nativeWrapper.isNativelyConnected() )
//		{
//			if( !attemptingReconnect )
//			{
		//--- DRK > Now doing this at top of method...no harm really and catches fringe case logic erros upstream. 
//				m_nativeWrapper.closeGattIfNeeded(/*disconnectAlso=*/true);
//			}
//		}
		
		boolean wasConnecting = is(CONNECTING_OVERALL);
		
		if( isAny(CONNECTED, CONNECTING, CONNECTING_OVERALL) )
		{
			setStateToDisconnected(attemptingReconnect, /*fromBleCallback=*/false, E_Intent.IMPLICIT);
		}
		
		if( wasConnecting )
		{
			ConnectionFailListener.Reason reason = state == PE_TaskState.FAILED_IMMEDIATELY ? ConnectionFailListener.Reason.NATIVE_CONNECTION_FAILED_IMMEDIATELY : ConnectionFailListener.Reason.NATIVE_CONNECTION_FAILED_EVENTUALLY;
			
			if( state == PE_TaskState.TIMED_OUT )
			{
				reason = Reason.NATIVE_CONNECTION_TIMED_OUT;
			}

			PE_Please retry = m_connectionFailMngr.onConnectionFailed(reason, attemptingReconnect, gattStatus, highestState, autoConnectUsage);
			
			if( !attemptingReconnect && retry == PE_Please.RETRY_WITH_AUTOCONNECT_TRUE )
			{
				m_useAutoConnect = true;
			}
			else if( !attemptingReconnect && retry == PE_Please.RETRY_WITH_AUTOCONNECT_FALSE )
			{
				m_useAutoConnect = false;
			}
			else
			{
				m_useAutoConnect = m_alwaysUseAutoConnect;
			}
		}
	}
	
	void onServicesDiscovered()
	{
		m_serviceMngr.clear();
		m_serviceMngr.loadDiscoveredServices();
		
		m_txnMngr.runAuthOrInitTxnIfNeeded(GETTING_SERVICES, false);
	}
	
	void onFullyInitialized(Object ... extraFlags)
	{
		m_mngr.onConnectionSucceeded();
		m_reconnectMngr.stop();
		m_connectionFailMngr.onFullyInitialized();
		
		//--- DRK > Saving last disconnect as unintentional here in case for some
		//---		reason app is hard killed or something and we never get a disconnect callback.
		final boolean hitDisk = BleDeviceConfig.bool(conf_device().manageLastDisconnectOnDisk, conf_mngr().manageLastDisconnectOnDisk);
		m_mngr.m_lastDisconnectMngr.save(getMacAddress(), State.ChangeIntent.UNINTENTIONAL, hitDisk);
		
		m_stateTracker.update
		(
			lastConnectDisconnectIntent(),
			extraFlags, ATTEMPTING_RECONNECT, false, CONNECTING_OVERALL, false,
			AUTHENTICATING, false, AUTHENTICATED, true, INITIALIZING, false, INITIALIZED, true
		);
	}
	
	private void setStateToDisconnected(boolean attemptingReconnect, boolean fromBleCallback, E_Intent intent)
	{
		//--- DRK > Device probably wasn't advertising while connected so here we reset the timer to keep
		//---		it from being immediately undiscovered after disconnection.
		m_timeSinceLastDiscovery = 0.0;
		
		m_serviceMngr.clear();
		m_txnMngr.clearQueueLock();
		
		m_stateTracker.set
		(
			intent,
			DISCOVERED, true,
			DISCONNECTED, true,
			BONDING, m_nativeWrapper.isNativelyBonding(),
			BONDED, m_nativeWrapper.isNativelyBonded(),
			UNBONDED, m_nativeWrapper.isNativelyUnbonded(),
			ATTEMPTING_RECONNECT, attemptingReconnect,
			ADVERTISING, !attemptingReconnect
		);
	}
	
	void disconnectWithReason(ConnectionFailListener.Reason connectionFailReasonIfConnecting, int gattStatus)
	{
		disconnectWithReason(null, connectionFailReasonIfConnecting, gattStatus);
	}
	
	void disconnectWithReason(PE_TaskPriority disconnectPriority_nullable, ConnectionFailListener.Reason connectionFailReasonIfConnecting, int gattStatus)
	{
		boolean cancelled = connectionFailReasonIfConnecting != null && connectionFailReasonIfConnecting.wasCancelled();
		final BleDeviceState highestState = BleDeviceState.getTransitoryConnectionState(getStateMask());
		
		if( cancelled )
		{
			m_useAutoConnect = m_alwaysUseAutoConnect;
			
			m_connectionFailMngr.onExplicitDisconnect();
		}
		
		boolean wasConnecting = is(CONNECTING_OVERALL);
		boolean attemptingReconnect = is(ATTEMPTING_RECONNECT);
		
		if( cancelled )
		{
			attemptingReconnect = false;
		}
		
		E_Intent intent = cancelled ? E_Intent.EXPLICIT : E_Intent.IMPLICIT;
		m_lastConnectOrDisconnectWasUserExplicit = intent == E_Intent.EXPLICIT;
		
		if( isAny(CONNECTED, CONNECTING_OVERALL, INITIALIZED) )
		{
			setStateToDisconnected(attemptingReconnect, /*fromBleCallback=*/false, intent);
			
			m_txnMngr.cancelAllTransactions();
//				m_txnMngr.clearAllTxns();
			
			if( !attemptingReconnect )
			{
				m_reconnectMngr.stop();
			}
		}
		else
		{
			if( !attemptingReconnect )
			{
				m_stateTracker.update(intent, ATTEMPTING_RECONNECT, false);
				m_reconnectMngr.stop();
			}
		}
		
		m_queue.add(new P_Task_Disconnect(this, m_taskStateListener, /*explicit=*/true, disconnectPriority_nullable));
		
		if( wasConnecting )
		{
			if( m_mngr.ASSERT(connectionFailReasonIfConnecting != null ) )
			{
				m_connectionFailMngr.onConnectionFailed(connectionFailReasonIfConnecting, attemptingReconnect, gattStatus, highestState, AutoConnectUsage.NOT_APPLICABLE);
			}
		}
	}
	
	void onDisconnecting()
	{
	}
	
	boolean lastDisconnectWasBecauseOfBleTurnOff()
	{
		return m_lastDisconnectWasBecauseOfBleTurnOff;
	}
	
	void onNativeDisconnect(final boolean wasExplicit)
	{
		if( !wasExplicit )
		{
			//--- DRK > Just here so it's easy to filter out in logs.
			m_logger.w("Disconnected Implicitly!");
		}
		
		m_lastDisconnectWasBecauseOfBleTurnOff = m_mngr.isAny(BleManagerState.TURNING_OFF, BleManagerState.OFF);
		
		m_lastConnectOrDisconnectWasUserExplicit = wasExplicit;
		
		BleDeviceState highestState = BleDeviceState.getTransitoryConnectionState(getStateMask());
		
//		if( m_state.ordinal() < E_State.CONNECTING.ordinal() )
//		{
//			m_logger.w("Already disconnected!");
//			
//			m_mngr.ASSERT(m_gatt == null);
//			
//			return;
//		}
		
		m_pollMngr.resetNotifyStates();
		
		boolean attemptingReconnect = false;
		
		m_nativeWrapper.closeGattIfNeeded(/*disconnectAlso=*/false);
		
		if( !wasExplicit )
		{
			if( is(INITIALIZED) )
			{
				m_reconnectMngr.start();
				attemptingReconnect = m_reconnectMngr.isRunning();
			}
		}
		else
		{
			m_connectionFailMngr.onExplicitDisconnect();
		}
		
		ConnectionFailListener.Reason connectionFailReason_nullable = null;
		final boolean isConnectingOverall = is(CONNECTING_OVERALL);
		
		if( isConnectingOverall )
		{
			if( m_mngr.ASSERT(!wasExplicit) )
			{
				if( m_mngr.isAny(BleManagerState.TURNING_OFF, BleManagerState.OFF) )
				{
					connectionFailReason_nullable = ConnectionFailListener.Reason.BLE_TURNING_OFF;
				}
				else
				{
					connectionFailReason_nullable = ConnectionFailListener.Reason.ROGUE_DISCONNECT;
				}
			}
		}
		
		final boolean hitDisk = BleDeviceConfig.bool(conf_device().manageLastDisconnectOnDisk, conf_mngr().manageLastDisconnectOnDisk);
		
		if( wasExplicit )
		{
			m_mngr.m_lastDisconnectMngr.save(getMacAddress(), State.ChangeIntent.INTENTIONAL, hitDisk);
		}
		else if( !isConnectingOverall )
		{
			m_mngr.m_lastDisconnectMngr.save(getMacAddress(), State.ChangeIntent.UNINTENTIONAL, hitDisk);
		}
		
		attemptingReconnect = attemptingReconnect || is(ATTEMPTING_RECONNECT);
		setStateToDisconnected(attemptingReconnect, /*fromBleCallback=*/true, wasExplicit ? E_Intent.EXPLICIT : E_Intent.IMPLICIT);
		
		if( !attemptingReconnect )
		{
			//--- DRK > Should be overkill cause disconnect call itself should have cleared these.
			m_txnMngr.cancelAllTransactions();
//			m_txnMngr.clearAllTxns();
		}
		else
		{
			m_txnMngr.cancelAllTransactions();
//			m_txnMngr.clearFirmwareUpdateTxn();
		}
		
		if( !wasExplicit )
		{
			m_queue.softlyCancelTasks(m_dummyDisconnectTask);
		}
		
		PE_Please retrying = m_connectionFailMngr.onConnectionFailed(connectionFailReason_nullable, attemptingReconnect, BleDeviceConfig.GATT_STATUS_NOT_APPLICABLE, highestState, AutoConnectUsage.NOT_APPLICABLE);
		
		//--- DRK > Not actually entirely sure how, it may be legitimate, but a connect task can still be
		//---		hanging out in the queue at this point, so we just make sure to clear the queue as a failsafe.
		//---		TODO: Understand the conditions under which a connect task can still be queued...might be a bug upstream.
		if( retrying == PE_Please.DO_NOT_RETRY )
		{
			m_queue.clearQueueOf(P_Task_Connect.class, this);
		}
	}
	
	private void stopPoll_private(UUID uuid, Double interval, ReadWriteListener listener)
	{
		m_pollMngr.stopPoll(uuid, interval, listener, /*usingNotify=*/false);
	}
	
	void read_internal(UUID uuid, Type type, P_WrappingReadWriteListener listener)
	{
		Result earlyOut = m_serviceMngr.getEarlyOutResult(uuid, EMPTY_BYTE_ARRAY, type);
		
		if( earlyOut != null )
		{
			if( listener != null )
			{
				listener.onResult(earlyOut);
			}
			
			return;
		}
		
		P_Characteristic characteristic = m_serviceMngr.getCharacteristic(uuid);
		
		read_internal(characteristic, type, listener);
	}
	
	void read_internal(P_Characteristic characteristic, Type type, P_WrappingReadWriteListener listener)
	{
		boolean requiresBonding = bondIfNeeded(characteristic, BondFilter.CharacteristicEventType.READ);
		
		m_queue.add(new P_Task_Read(characteristic, type, requiresBonding, listener, m_txnMngr.getCurrent(), getOverrideReadWritePriority()));
	}
	
	void write_internal(UUID uuid, byte[] data, P_WrappingReadWriteListener listener)
	{
		Result earlyOutResult = m_serviceMngr.getEarlyOutResult(uuid, data, Type.WRITE);
		
		if( earlyOutResult != null )
		{
			if( listener != null )
			{
				listener.onResult(earlyOutResult);
			}
			
			return;
		}
		
		P_Characteristic characteristic = m_serviceMngr.getCharacteristic(uuid);
		
		boolean requiresBonding = bondIfNeeded(characteristic, BondFilter.CharacteristicEventType.WRITE);
		
		m_queue.add(new P_Task_Write(characteristic, data, requiresBonding, listener, m_txnMngr.getCurrent(), getOverrideReadWritePriority()));
	}
	
	private void disableNotify_private(UUID uuid, Double forceReadTimeout, ReadWriteListener listener)
	{
		Result earlyOutResult = m_serviceMngr.getEarlyOutResult(uuid, EMPTY_BYTE_ARRAY, Type.DISABLING_NOTIFICATION);
		
		if( earlyOutResult != null )
		{
			if( listener != null )
			{
				listener.onResult(earlyOutResult);
			}
			
			return;
		}
		
		P_Characteristic characteristic = m_serviceMngr.getCharacteristic(uuid);
		
		if( characteristic != null && is(CONNECTED) )
		{
			P_WrappingReadWriteListener wrappingListener = new P_WrappingReadWriteListener(listener, m_mngr.m_mainThreadHandler, m_mngr.m_config.postCallbacksToMainThread);
			m_queue.add(new P_Task_ToggleNotify(characteristic, /*enable=*/false, wrappingListener));
		}
		
		m_pollMngr.stopPoll(uuid, forceReadTimeout, listener, /*usingNotify=*/true);
	}
	
	private boolean bondIfNeeded(final P_Characteristic characteristic, final BondFilter.CharacteristicEventType type)
	{
		final BleDeviceConfig.BondFilter bondFilter = conf_device().bondFilter != null ? conf_device().bondFilter : conf_mngr().bondFilter;
		
		if( bondFilter == null )  return false;
		
		final BondFilter.CharacteristicEvent event = new BondFilter.CharacteristicEvent(this, characteristic.getUuid(), type);
		
		final BondFilter.Please please = bondFilter.onCharacteristicEvent(event);
		
		return applyPlease_BondFilter(please);
	}
	
	boolean applyPlease_BondFilter(BondFilter.Please please_nullable)
	{
		if( please_nullable == null )
		{
			return false;
		}
		
		final Boolean bond = please_nullable.bond_private();
		
		if( bond == null )
		{
			return false;
		}
		
		if( bond )
		{
			bond();
		}
		else if( !bond )
		{
			unbond();
		}
		
		return bond;
	}
	
	E_Intent lastConnectDisconnectIntent()
	{
		if( m_lastConnectOrDisconnectWasUserExplicit == null )
		{
			return E_Intent.IMPLICIT;
		}
		else
		{
			return m_lastConnectOrDisconnectWasUserExplicit ? E_Intent.EXPLICIT : E_Intent.IMPLICIT;
		}
	}
	
	private PE_TaskPriority getOverrideReadWritePriority()
	{
		if( isAny(AUTHENTICATING, INITIALIZING) )
		{
			m_mngr.ASSERT(m_txnMngr.getCurrent() != null);
			
			return PE_TaskPriority.FOR_PRIORITY_READS_WRITES; 
		}
		else
		{
			return PE_TaskPriority.FOR_NORMAL_READS_WRITES;
		}
	}
}
