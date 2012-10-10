package play.modules.play-airbrake;

import play.Logger;
import play.PlayPlugin;

import com.blitzoo.log4j.AirbrakeAppender;

public class PlayAirbrakePlugin extends PlayPlugin {
	@Override
	public void onApplicationStart() {
		AirbrakeAppender appender = new AirbrakeAppender();
		Logger.log4j.addAppender(appender);
	}
}