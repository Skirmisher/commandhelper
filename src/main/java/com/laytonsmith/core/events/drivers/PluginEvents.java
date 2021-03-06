package com.laytonsmith.core.events.drivers;

import com.laytonsmith.abstraction.events.MCPluginIncomingMessageEvent;
import com.laytonsmith.annotations.api;
import com.laytonsmith.core.CHVersion;
import com.laytonsmith.core.constructs.CArray;
import com.laytonsmith.core.constructs.CByteArray;
import com.laytonsmith.core.constructs.CString;
import com.laytonsmith.core.constructs.Construct;
import com.laytonsmith.core.constructs.Target;
import com.laytonsmith.core.events.AbstractEvent;
import com.laytonsmith.core.events.BindableEvent;
import com.laytonsmith.core.events.Driver;
import com.laytonsmith.core.events.Prefilters;
import com.laytonsmith.core.exceptions.EventException;
import com.laytonsmith.core.exceptions.PrefilterNonMatchException;
import java.util.Map;

/**
 *
 * @author Jason Unger <entityreborn@gmail.com>
 */
public class PluginEvents {
	@api
	public static class plugin_message_received extends AbstractEvent {

		@Override
		public boolean isCancellable(BindableEvent o) {
			return false;
		}

		@Override
		public String getName() {
			return "plugin_message_received";
		}

		@Override
		public String docs() {
			return "{channel: <string match>}"
					+ " Fires when a player's client sends a plugin message."
					+ " {player: the player | channel: the channel used |"
					+ " bytes: byte array of the data sent}"
					+ " {}"
					+ " {player|channel|bytes}";
		}

		@Override
		public boolean matches(Map<String, Construct> prefilter, BindableEvent e) throws PrefilterNonMatchException {
			if (e instanceof MCPluginIncomingMessageEvent) {
				MCPluginIncomingMessageEvent event = (MCPluginIncomingMessageEvent)e;
				
				Prefilters.match(prefilter, "channel", event.getChannel(), Prefilters.PrefilterType.STRING_MATCH);
				
				return true;
			}
			return false;
		}

		@Override
		public BindableEvent convert(CArray manualObject) {
			return null;
		}

		@Override
		public Map<String, Construct> evaluate(BindableEvent e) throws EventException {
			if (e instanceof MCPluginIncomingMessageEvent) {
				MCPluginIncomingMessageEvent event = (MCPluginIncomingMessageEvent) e;
				Map<String, Construct> ret = evaluate_helper(e);
				
				ret.put("channel", new CString(event.getChannel(), Target.UNKNOWN));
				ret.put("player", new CString(event.getPlayer().getName(), Target.UNKNOWN));

				// Insert bytes into a CByteArray
				CByteArray a = CByteArray.wrap(event.getBytes().clone(), Target.UNKNOWN);
				
				a.rewind();
				
				ret.put("bytes", a);
				
				return ret;
			} else {
				throw new EventException("Cannot convert to MCPluginIncomingMessageEvent");
			}
		}

		@Override
		public Driver driver() {
			return Driver.PLUGIN_MESSAGE_RECEIVED;
		}

		@Override
		public boolean modifyEvent(String key, Construct value, BindableEvent event) {
			return false;
		}

		@Override
		public CHVersion since() {
			return CHVersion.V3_3_1;
		}
	}
}
