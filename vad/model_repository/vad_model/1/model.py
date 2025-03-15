import os
import json
import base64

import numpy as np
import torch
import torch.nn.functional as F
from typing import Dict
# triton_python_backend_utils is available in every Triton Python model. You
# need to use this module to create inference requests and responses. It also
# contains some utility functions for extracting information from model_config
# and converting Triton input/output types to numpy types.
import triton_python_backend_utils as pb_utils

MINIMUM_SAMPLES_FOR_VAD: int = 256  # VAD모델 입력에 필요한 최소 오디오 샘플 수

MIN_SILENCE_DURATION_MS: int = int(os.environ.get("MIN_SILENCE_DURATION_MS", 500))
MAX_SPEECH_DURATION_MS: int = int(os.environ.get("MAX_SPEECH_DURATION_MS", 20000))
MIN_SILENCE_SAMPLES: int = int(
    8000 * MIN_SILENCE_DURATION_MS / 1000
)  # 묵음 판단에 필요한 최소 오디오 샘플 수. 최소 해당 샘플만큼의 묵음 데이터가 있어야 묵음이라고 판단한다. (ex. 800 샘플일경우, 100ms의 묵음이 발샣해야 발화 종료 판단.)

class TritonPythonModel:
    """Your Python model must use the same class name. Every Python model
    that is created must have "TritonPythonModel" as the class name.
    """

    def initialize(self, args):
        """`initialize` is called only once when the model is being loaded.
        Implementing `initialize` function is optional. This function allows
        the model to initialize any state associated with this model.
        Parameters
        ----------
        args : dict
          Both keys and values are strings. The dictionary keys and values are:
          * model_config: A JSON string containing the model configuration
          * model_instance_kind: A string containing model instance kind
          * model_instance_device_id: A string containing model instance device ID
          * model_repository: Model repository path
          * model_version: Model version
          * model_name: Model name
        """

        # You must parse model_config. JSON string is not parsed here
        self.model_name = args["model_name"]
        self.model_version = args["model_version"]
        self.model_config = json.loads(args["model_config"])

        self.logger = pb_utils.Logger
        self.device_id = args["model_instance_device_id"]
        self.device = "cpu"
   

        self.init_model(self.model_config["parameters"])

    def init_model(self, parameters):
        for key, value in parameters.items():
            parameters[key] = value["string_value"]
        model_dir = parameters["model_dir"]
        print(f"MODEL DIR!!!!!!!!!!!!!!!! : {model_dir}")
        self.model, VADIterator = torch.hub.load(source="local", repo_or_dir=model_dir, model="silero_vad", onnx=True)
        print(f"MODEL : {self.model}")
        print(f"VADIterator : {VADIterator}")
        self.vad_iterator: VADIterator = VADIterator(
            self.model,
            sampling_rate=16000,
            min_silence_duration_ms=500,
        )

    def detect_speech(self, audio_chunk: bytes) -> Dict[str, float]:
        audio_tensor: torch.tensor = torch.frombuffer(audio_chunk, dtype=torch.int16).float() / 32768.0
        audio_tensor: torch.tensor = audio_tensor.unsqueeze(0)
        audio_samples: int = audio_tensor.shape[1]
        if audio_samples < MINIMUM_SAMPLES_FOR_VAD:
            padding_length = MIN_SILENCE_SAMPLES - audio_samples
            padded_audio_tensor = F.pad(audio_tensor, (0, padding_length), "constant", 0)
            vad_event, speech_prob = self.vad_iterator(padded_audio_tensor, return_seconds=True)
            
            
        else:
            vad_event,speech_prob = self.vad_iterator(audio_tensor, return_seconds=True)
            
   
        return vad_event, speech_prob
    
    def execute(self, requests):
        """`execute` MUST be implemented in every Python model. `execute`
        function receives a list of pb_utils.InferenceRequest as the only
        argument. This function is called when an inference request is made
        for this model. Depending on the batching configuration (e.g. Dynamic
        Batching) used, `requests` may contain multiple requests. Every
        Python model, must create one pb_utils.InferenceResponse for every
        pb_utils.InferenceRequest in `requests`. If there is an error, you can
        set the error argument when creating a pb_utils.InferenceResponse
        Parameters
        ----------
        requests : list
          A list of pb_utils.InferenceRequest
        Returns
        -------
        list
          A list of pb_utils.InferenceResponse. The length of this list must
          be the same as `requests`
        """

        audio_tensors = []
        responses = []

        # Every Python backend must iterate over everyone of the requests
        # and create a pb_utils.InferenceResponse for each of them.
        self.logger.log_info(
            f"[{self.model_name} v{self.model_version}] Starting execute function with {len(requests)} requests."
        )
        for request in requests:  
            start_flag = pb_utils.get_input_tensor_by_name(request, "START").as_numpy()[0]
            end_flag = pb_utils.get_input_tensor_by_name(request, "END").as_numpy()[0]
            if start_flag:
                self.logger.log_info("START OF STREAM!!!!!")
                self.vad_iterator.reset_states()
                vad_event_out = np.array(["STARTOFSTREAM"], dtype=object)
                timestamp_out = np.array([-1], dtype=np.float32)
                out_tensor_event = pb_utils.Tensor("EVENT", vad_event_out)
                out_tensor_timestamp = pb_utils.Tensor("TIMESTAMP", timestamp_out)
                inference_response = pb_utils.InferenceResponse(output_tensors=[out_tensor_event, out_tensor_timestamp])
                responses.append(inference_response)
                return responses
            if end_flag:
                self.logger.log_info("END OF STREAM!!!!!")
                self.vad_iterator.reset_states()
                vad_event_out = np.array(["ENDOFSTREAM"], dtype=object)
                timestamp_out = np.array([-1], dtype=np.float32)
                out_tensor_event = pb_utils.Tensor("EVENT", vad_event_out)
                out_tensor_timestamp = pb_utils.Tensor("TIMESTAMP", timestamp_out)
                inference_response = pb_utils.InferenceResponse(output_tensors=[out_tensor_event, out_tensor_timestamp])
                responses = [inference_response]
                return responses
            else:
                audio_chunk:bytes = base64.b64decode(pb_utils.get_input_tensor_by_name(request, "AUDIO_CHUNK").as_numpy()[0])
                vad_event, speech_prob = self.detect_speech(audio_chunk)
                self.logger.log_info(f"SPEECH_PROB : {speech_prob}")
                if vad_event:
                    event:str = list(vad_event.keys())[0]
                    timestamp:float = list(vad_event.values())[0]
                    
                   
                else:
                    self.logger.log_info(f"NOSPEECH!!!!!!!!")
                    event:str = "NOSPEECH"
                    timestamp:float = -1
                        
    
                vad_event_out = np.array([event], dtype=object)
                timestamp_out = np.array([timestamp], dtype=np.float32)

                out_tensor_event = pb_utils.Tensor("EVENT", vad_event_out)
                out_tensor_timestamp = pb_utils.Tensor("TIMESTAMP", timestamp_out)
                inference_response = pb_utils.InferenceResponse(output_tensors=[out_tensor_event, out_tensor_timestamp])
                responses.append(inference_response)
          

        # Create InferenceResponse. You can set an error here in case
        # there was a problem with handling this inference request.
        # Below is an example of how you can set errors in inference
        # response:
        #
        # pb_utils.InferenceResponse(
        # output_tensors=..., TritonError("An error occurred"))
        # You should return a list of pb_utils.InferenceResponse. Length
        # of this list must match the length of `requests` list.

        return responses

    def finalize(self):
        """`finalize` is called only once when the model is being unloaded.
        Implementing `finalize` function is OPTIONAL. This function allows
        the model to perform any necessary clean ups before exit.
        """
        print("Cleaning up...")
